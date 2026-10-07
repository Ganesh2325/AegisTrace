package com.aegistrace.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MetricsService {
    public static final String[] READ_ROLES = {"OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN"};

    static final String RUNS_SQL = """
            select r.state, r.model, r.input_tokens, r.output_tokens, r.estimated_cost_usd,
                   case
                       when r.started_at is null or r.ended_at is null then null
                       else extract(epoch from (r.ended_at - r.started_at)) * 1000
                   end as duration_ms
            from agent_runs r
            where r.workspace_id = :workspace
              and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
            """;

    static final String APPROVALS_SQL = """
            select a.status,
                   case
                       when a.decided_at is null or a.requested_at is null then null
                       else extract(epoch from (a.decided_at - a.requested_at)) * 1000
                   end as wait_ms
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
            var runs = jdbc.query(RUNS_SQL + vis + runWindow, params, (rs, n) -> new MetricsCalculator.RunSample(
                    rs.getString("state"),
                    number(rs.getObject("duration_ms")),
                    rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"),
                    rs.getBigDecimal("estimated_cost_usd"),
                    rs.getString("model")));
            var approvals = jdbc.query(APPROVALS_SQL + approvalWindow, params, (rs, n) -> new MetricsCalculator.ApprovalSample(
                    rs.getString("status"),
                    number(rs.getObject("wait_ms"))));
            Map<String, Object> counts = jdbc.queryForMap(COUNTS_SQL, params);
            var body = MetricsCalculator.summarize(
                    runs,
                    approvals,
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
