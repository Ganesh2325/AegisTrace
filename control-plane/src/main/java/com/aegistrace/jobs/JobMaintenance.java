package com.aegistrace.jobs;

import com.aegistrace.audit.AuditService;
import com.aegistrace.run.RunEventBus;
import com.aegistrace.run.RunStateMachine;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

@Component
public class JobMaintenance {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RunEventBus events;
    private final MeterRegistry meters;
    private final AuditService audit;

    public JobMaintenance(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, RunEventBus events, MeterRegistry meters,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.events = events;
        this.meters = meters;
        this.audit = audit;
        meters.gauge("aegis.queue.depth", this, JobMaintenance::queueDepth);
    }

    @Scheduled(fixedDelay = 5000)
    public void maintain() {
        reapJobs();
        expireApprovals();
        timeOutRuns();
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void retain() {
        jdbc.update("""
                update agent_runs r set question = '[redacted]', draft_answer = '[redacted]', final_response = '[redacted]'
                from workspace_settings s
                where r.workspace_id = s.workspace_id
                  and r.created_at < now() - (s.question_retention_days || ' days')::interval
                  and r.question <> '[redacted]'
                """, Map.of());
        jdbc.update("""
                update tool_proposals p set arguments = '{}'::jsonb
                from agent_runs r join workspace_settings s on s.workspace_id = r.workspace_id
                where p.run_id = r.id
                  and r.created_at < now() - (s.question_retention_days || ' days')::interval
                  and p.arguments <> '{}'::jsonb
                """, Map.of());
    }

    private void reapJobs() {
        int reaped = jdbc.update("""
                update jobs set
                    status = case when attempts >= max_attempts then 'DEAD' else 'RETRY' end,
                    locked_by = null,
                    locked_until = null,
                    next_run_at = now(),
                    updated_at = now(),
                    last_error = coalesce(last_error, 'lease expired')
                where status = 'RUNNING' and locked_until < now()
                """, Map.of());
        if (reaped > 0) {
            meters.counter("aegis.job.lease.expired").increment(reaped);
        }
    }

    private void expireApprovals() {
        var ids = jdbc.query("""
                select a.id, a.run_id, a.workspace_id from approvals a
                where a.status = 'PENDING' and a.expires_at < now()
                """, Map.of(), (rs, n) -> new UUID[]{
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("run_id")),
                UUID.fromString(rs.getString("workspace_id"))
        });
        for (UUID[] row : ids) {
            tx.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                        update approvals set status = 'EXPIRED', decided_at = now()
                        where id = :id and status = 'PENDING'
                        """, Map.of("id", row[0]));
                if (updated == 0) {
                    return;
                }
                meters.counter("aegis.approval.expired").increment();
                events.append(row[1], "APPROVAL_EXPIRED", "APPROVAL_REQUIRED", Map.of("approvalId", row[0]));
                audit.record(row[2], null, "APPROVAL_EXPIRED", "approval", row[0].toString(), row[1], Map.of());
                String state = jdbc.queryForObject("select state from agent_runs where id = :id for update", Map.of("id", row[1]), String.class);
                if (RunStateMachine.canTransition(state, "FAILED")) {
                    jdbc.update("""
                            update agent_runs set state = 'FAILED', failure_category = 'APPROVAL_EXPIRED', error_code = 'APPROVAL_EXPIRED',
                                error_message = 'The approval expired.', ended_at = now()
                            where id = :id
                            """, Map.of("id", row[1]));
                    events.append(row[1], "RUN_FAILED", "FAILED", Map.of("category", "APPROVAL_EXPIRED"));
                }
            });
        }
    }

    private void timeOutRuns() {
        var ids = jdbc.query("""
                select id, state from agent_runs
                where timeout_at < now()
                  and state not in ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'APPROVAL_REQUIRED')
                """, Map.of(), (rs, n) -> new String[]{rs.getString("id"), rs.getString("state")});
        for (String[] row : ids) {
            UUID id = UUID.fromString(row[0]);
            if (!RunStateMachine.canTransition(row[1], "TIMED_OUT")) {
                continue;
            }
            tx.executeWithoutResult(status -> {
                int updated = jdbc.update("""
                        update agent_runs set state = 'TIMED_OUT', failure_category = 'TIMED_OUT', error_code = 'TIMED_OUT',
                            error_message = 'The run timed out.', ended_at = now()
                        where id = :id and state = :state
                        """, new MapSqlParameterSource().addValue("id", id).addValue("state", row[1]));
                if (updated == 1) {
                    meters.counter("aegis.timeout", "kind", "run").increment();
                    var pending = jdbc.query(
                            "select id, workspace_id from approvals where run_id = :id and status = 'PENDING'",
                            Map.of("id", id), (rs, n) -> new UUID[]{
                                    UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("workspace_id"))});
                    jdbc.update("update approvals set status = 'EXPIRED', decided_at = now() where run_id = :id and status = 'PENDING'",
                            Map.of("id", id));
                    for (UUID[] approval : pending) {
                        events.append(id, "APPROVAL_EXPIRED", "TIMED_OUT", Map.of("approvalId", approval[0]));
                        audit.record(approval[1], null, "APPROVAL_EXPIRED", "approval", approval[0].toString(), id, Map.of("runTimeout", true));
                    }
                    events.append(id, "RUN_TIMED_OUT", "TIMED_OUT", Map.of("category", "TIMED_OUT"));
                    meters.counter("aegis.runs", "result", "timed_out").increment();
                }
            });
        }
    }

    private double queueDepth() {
        try {
            Long depth = jdbc.queryForObject(
                    "select count(*) from jobs where status in ('PENDING','RETRY','RUNNING')", Map.of(), Long.class);
            return depth == null ? 0 : depth;
        } catch (Exception ex) {
            return Double.NaN;
        }
    }
}
