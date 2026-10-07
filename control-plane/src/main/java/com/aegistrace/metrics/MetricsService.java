package com.aegistrace.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MetricsService {
    public static final String[] READ_ROLES = {"OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN"};

    static final String RUNS_SQL = """
            select count(*) as runs,
                   count(*) filter (where r.state not in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT','APPROVAL_REQUIRED')) as active,
                   count(*) filter (where r.state = 'APPROVAL_REQUIRED') as waiting,
                   count(*) filter (where r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')) as terminal,
                   count(*) filter (where r.state = 'COMPLETED') as completed,
                   count(*) filter (where r.state = 'FAILED') as failed,
                   count(*) filter (where r.state = 'TIMED_OUT') as timed_out,
                   count(*) filter (where r.state = 'CANCELLED') as cancelled,
                   coalesce(sum(r.input_tokens + r.output_tokens), 0) as tokens,
                   coalesce(sum(r.estimated_cost_usd), 0) as cost,
                   count(*) filter (
                       where r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                         and r.started_at is not null and r.ended_at is not null and r.ended_at >= r.started_at
                   ) as valid_durations,
                   count(*) filter (
                       where (r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                              and (r.started_at is null or r.ended_at is null or r.ended_at < r.started_at))
                          or (r.state not in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                              and r.started_at is not null and r.ended_at is not null and r.ended_at < r.started_at)
                   ) as invalid_durations,
                   percentile_cont(0.50) within group (
                       order by extract(epoch from (r.ended_at - r.started_at)) * 1000
                   ) filter (where r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                       and r.started_at is not null and r.ended_at is not null and r.ended_at >= r.started_at) as p50,
                   percentile_cont(0.95) within group (
                       order by extract(epoch from (r.ended_at - r.started_at)) * 1000
                   ) filter (where r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                       and r.started_at is not null and r.ended_at is not null and r.ended_at >= r.started_at) as p95,
                   percentile_cont(0.99) within group (
                       order by extract(epoch from (r.ended_at - r.started_at)) * 1000
                   ) filter (where r.state in ('COMPLETED','FAILED','CANCELLED','TIMED_OUT')
                       and r.started_at is not null and r.ended_at is not null and r.ended_at >= r.started_at) as p99,
                   coalesce(bool_or(r.model = 'gpt-4o-mini'), false) as has_list_price,
                   coalesce(bool_or(r.model not in ('gpt-4o-mini','grounded-extractive-v1')), false) as has_unknown_price
            from agent_runs r
            where r.workspace_id = :workspace
              and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
            """;

    static final String APPROVALS_SQL = """
            select a.status, count(*) as total,
                   count(*) filter (where a.decided_at is not null and a.requested_at is not null
                       and a.decided_at >= a.requested_at) as samples,
                   count(*) filter (where a.status in ('APPROVED','REJECTED','EXPIRED')
                       and (a.decided_at is null or a.requested_at is null or a.decided_at < a.requested_at)) as invalid,
                   avg(extract(epoch from (a.decided_at - a.requested_at)) * 1000)
                       filter (where a.decided_at is not null and a.requested_at is not null
                           and a.decided_at >= a.requested_at) as mean_ms
            from approvals a
            join agent_runs r on r.id = a.run_id
            where a.workspace_id = :workspace
              and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
            """;

    static final String COUNTS_SQL = """
            select
                (select count(*) from jobs
                    where workspace_id = :workspace and status in ('PENDING', 'RETRY', 'RUNNING')
                      and job_type not in ('START_EVALUATION', 'EVALUATE_CASE')) as queue_depth,
                (select count(*) from tool_proposals p
                    join agent_runs r on r.id = p.run_id
                    where r.workspace_id = :workspace and p.policy_decision = 'DENY'
                      and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)) as denials,
                (select count(*) from tool_proposals p
                    join agent_runs r on r.id = p.run_id
                    where r.workspace_id = :workspace and p.policy_decision is not null
                      and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)) as decisions,
                (select count(*) from evaluations where workspace_id = :workspace) as evaluations,
                (select count(*) from evaluations where workspace_id = :workspace and passed) as evaluation_passes
            """;

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final MeterRegistry meters;

    public MetricsService(NamedParameterJdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public Map<String, Object> summary(UUID workspaceId) {
        return summary(workspaceId, MetricsCalculator.Window.allTime(), " ", null);
    }

    public Map<String, Object> summary(UUID workspaceId, MetricsCalculator.Window window, String visibility, UUID userId) {
        long started = System.nanoTime();
        MetricsCalculator.Window resolved = window == null ? MetricsCalculator.Window.allTime() : window;
        String vis = visibility == null ? " " : visibility;
        try {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("workspace", workspaceId)
                    .addValue("user", userId);
            if (!resolved.unbounded()) {
                params.addValue("windowStart", Timestamp.from(resolved.start()));
                params.addValue("windowEnd", Timestamp.from(resolved.end()));
            }
            String runWindow = resolved.sqlPredicate("r.created_at");
            String approvalWindow = resolved.sqlPredicate("a.requested_at");
            MetricsCalculator.RunAggregate runs = jdbc.queryForObject(
                    RUNS_SQL + vis + runWindow, params, (rs, n) -> {
                        Set<String> models = new HashSet<>();
                        if (rs.getLong("runs") > 0) models.add("grounded-extractive-v1");
                        if (rs.getBoolean("has_list_price")) models.add("gpt-4o-mini");
                        if (rs.getBoolean("has_unknown_price")) models.add("unknown");
                        return new MetricsCalculator.RunAggregate(
                                rs.getLong("runs"), rs.getLong("active"), rs.getLong("waiting"),
                                rs.getLong("terminal"), rs.getLong("completed"), rs.getLong("failed"),
                                rs.getLong("timed_out"), rs.getLong("cancelled"), rs.getLong("tokens"),
                                rs.getBigDecimal("cost") == null ? BigDecimal.ZERO : rs.getBigDecimal("cost"),
                                rs.getLong("valid_durations"), rs.getLong("invalid_durations"),
                                number(rs.getObject("p50")), number(rs.getObject("p95")), number(rs.getObject("p99")),
                                models);
                    });
            Map<String, MetricsCalculator.WaitAggregate> approvals = new HashMap<>();
            long[] pendingApprovals = {0};
            jdbc.query(APPROVALS_SQL + approvalWindow + " group by a.status", params, rs -> {
                String approvalStatus = rs.getString("status");
                if ("PENDING".equals(approvalStatus)) pendingApprovals[0] = rs.getLong("total");
                approvals.put(approvalStatus, new MetricsCalculator.WaitAggregate(
                        rs.getLong("samples"), rs.getLong("invalid"), number(rs.getObject("mean_ms"))));
            });
            Map<String, Object> counts = jdbc.queryForMap(COUNTS_SQL, params);
            var body = MetricsCalculator.summarizeAggregates(
                    runs,
                    approvals,
                    pendingApprovals[0],
                    ((Number) counts.get("queue_depth")).longValue(),
                    ((Number) counts.get("denials")).longValue(),
                    ((Number) counts.get("decisions")).longValue(),
                    ((Number) counts.get("evaluations")).longValue(),
                    ((Number) counts.get("evaluation_passes")).longValue(),
                    resolved);
            long elapsed = System.nanoTime() - started;
            meters.timer("aegis.metrics.summary").record(elapsed, TimeUnit.NANOSECONDS);
            log.info("metrics_summary workspace={} window={} duration_ms={}", workspaceId, resolved.code(), elapsed / 1_000_000);
            return body;
        } catch (DataAccessException ex) {
            log.error("metrics_summary_failed workspace={}", workspaceId);
            throw ex;
        }
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }
}
