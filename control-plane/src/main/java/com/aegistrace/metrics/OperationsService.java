package com.aegistrace.metrics;

import com.aegistrace.common.ApiException;
import com.aegistrace.run.RunService;
import com.aegistrace.run.RunStateMachine;
import com.aegistrace.security.Actor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OperationsService {
    public static final int RECENT_LIMIT = 20;
    public static final int ACTIVE_LIMIT = 20;
    public static final int FAILURE_LIMIT = 10;
    public static final int ACTIVITY_LIMIT = 20;
    public static final int APPROVAL_LIMIT = 10;
    public static final int QUEUE_ATTENTION = 5;
    public static final int LATENCY_TREND_MIN_DAYS = 2;

    static final String RUN_COLUMNS = """
            r.id, r.state, r.failure_category, r.estimated_cost_usd, r.created_at, r.started_at, r.ended_at,
            r.question, r.user_id, a.name as agent_name, a.id as agent_id, a.status as agent_status
            """;

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final MetricsService metrics;
    private final MeterRegistry meters;

    public OperationsService(NamedParameterJdbcTemplate jdbc, MetricsService metrics, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.meters = meters;
    }

    public Map<String, Object> overview(Actor actor, UUID workspaceId, String windowParam, String runFilter,
                                        int page, Instant now) {
        long started = System.nanoTime();
        MetricsCalculator.Window window;
        try {
            window = MetricsCalculator.Window.parse(windowParam, now);
        } catch (IllegalArgumentException ex) {
            throw new ApiException("INVALID_WINDOW", "Use 24H, 7D, 30D, or ALL.", 400);
        }
        String filter = RunService.visibilitySql(actor.membership(workspaceId).role());
        MapSqlParameterSource params = baseParams(workspaceId, actor.id(), window);
        try {
            Map<String, Object> summary = metrics.summary(workspaceId, window, filter, actor.id());
            List<Map<String, Object>> active = queryRuns(params, filter, window, activePredicate(), ACTIVE_LIMIT, 0);
            String recentPredicate = recentPredicate(runFilter);
            int offset = Math.max(page, 0) * RECENT_LIMIT;
            List<Map<String, Object>> recent = queryRuns(params, filter, window, recentPredicate, RECENT_LIMIT, offset);
            Long recentTotal = jdbc.queryForObject(
                    "select count(*) from agent_runs r where r.workspace_id = :workspace" + filter + window.sqlPredicate("r.created_at") + recentPredicate,
                    params, Long.class);
            List<Map<String, Object>> failures = queryRuns(params, filter, window,
                    " and r.state in ('FAILED', 'TIMED_OUT')", FAILURE_LIMIT, 0);
            List<Map<String, Object>> approvals = queryApprovals(params, window, actor.membership(workspaceId).role());
            List<Map<String, Object>> activity = queryActivity(params, filter, window);
            List<Map<String, Object>> agents = queryAgents(params, filter, window);
            List<Map<String, Object>> activityTrend = activityTrend(params, filter, window);
            List<Map<String, Object>> latencyTrend = latencyTrend(params, filter, window);
            var attention = attention(summary, active, approvals,
                    ((Number) ((Map<?, ?>) summary.get("queueDepth")).get("value")).longValue());

            var body = new LinkedHashMap<String, Object>();
            body.put("generatedAt", now.toString());
            body.put("summary", summary);
            body.put("activeRuns", active);
            body.put("recentRuns", Map.of(
                    "items", recent,
                    "page", Math.max(page, 0),
                    "size", RECENT_LIMIT,
                    "total", recentTotal == null ? 0 : recentTotal,
                    "filter", runFilter == null || runFilter.isBlank() ? "ALL" : runFilter.toUpperCase()
            ));
            body.put("recentFailures", failures);
            body.put("approvals", approvals);
            body.put("activity", activity);
            body.put("agents", agents);
            body.put("activityTrend", activityTrend);
            body.put("latencyTrend", latencyTrend);
            body.put("attention", attention);
            body.put("capabilities", Map.of(
                    "canCreateRun", "OPERATOR".equals(actor.membership(workspaceId).role()) || "ADMIN".equals(actor.membership(workspaceId).role()),
                    "canReadApprovals", "REVIEWER".equals(actor.membership(workspaceId).role()) || "ADMIN".equals(actor.membership(workspaceId).role())
            ));
            long elapsed = System.nanoTime() - started;
            meters.timer("aegis.operations.overview").record(elapsed, TimeUnit.NANOSECONDS);
            log.info("operations_overview workspace={} window={} duration_ms={}", workspaceId, window.code(), elapsed / 1_000_000);
            return body;
        } catch (DataAccessException ex) {
            log.error("operations_overview_failed workspace={}", workspaceId, ex);
            throw ex;
        }
    }

    static List<Map<String, Object>> attention(Map<String, Object> summary, List<Map<String, Object>> active,
                                               List<Map<String, Object>> approvals, long queueDepth) {
        var items = new ArrayList<Map<String, Object>>();
        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) summary.get("latency");
        if (Boolean.TRUE.equals(latency.get("high"))) {
            items.add(item("HIGH", "HIGH_LATENCY", "High latency observed",
                    "p95 is at or above the documented attention line of 300000 ms.", null, "/observability"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) summary.get("pendingApprovals");
        long pendingCount = ((Number) pending.get("value")).longValue();
        if (pendingCount > 0 && !approvals.isEmpty()) {
            items.add(item("HIGH", "PENDING_APPROVAL", "Approvals waiting",
                    pendingCount + " pending approval" + (pendingCount == 1 ? "" : "s") + " in this workspace.",
                    null, "/approvals"));
        }
        long timedOut = ((Number) ((Map<?, ?>) summary.get("timedOut")).get("value")).longValue();
        if (timedOut > 0) {
            items.add(item("HIGH", "TIMED_OUT", "Timed-out runs",
                    timedOut + " timed-out run" + (timedOut == 1 ? "" : "s") + " in this window.",
                    null, "/"));
        }
        long failed = ((Number) ((Map<?, ?>) summary.get("failed")).get("value")).longValue();
        if (failed > 0) {
            items.add(item("MEDIUM", "FAILED", "Failed runs",
                    failed + " failed run" + (failed == 1 ? "" : "s") + " in this window.",
                    null, "/"));
        }
        if (queueDepth >= QUEUE_ATTENTION) {
            items.add(item("MEDIUM", "QUEUE", "Queue depth",
                    queueDepth + " open jobs in this workspace.",
                    null, "/observability"));
        }
        for (Map<String, Object> run : active) {
            if ("APPROVAL_REQUIRED".equals(run.get("state"))) {
                items.add(item("HIGH", "ACTIVE_APPROVAL", "Run waiting for approval",
                        "A visible run is in APPROVAL_REQUIRED.",
                        String.valueOf(run.get("id")), "/runs/" + run.get("id")));
            }
        }
        return items;
    }

    private static Map<String, Object> item(String severity, String code, String title, String reason, String runId, String href) {
        var row = new LinkedHashMap<String, Object>();
        row.put("severity", severity);
        row.put("code", code);
        row.put("title", title);
        row.put("reason", reason);
        row.put("runId", runId);
        row.put("href", href);
        return row;
    }

    private List<Map<String, Object>> queryRuns(MapSqlParameterSource params, String visibility, MetricsCalculator.Window window, String extra, int limit, int offset) {
        var queryParams = new MapSqlParameterSource(params.getValues())
                .addValue("limit", limit)
                .addValue("offset", offset);
        return jdbc.query("""
                select %s,
                       case
                           when r.started_at is null or r.ended_at is null then null
                           else extract(epoch from (r.ended_at - r.started_at)) * 1000
                       end as duration_ms
                from agent_runs r
                join agents a on a.id = r.agent_id
                where r.workspace_id = :workspace
                """.formatted(RUN_COLUMNS) + visibility + window.sqlPredicate("r.created_at") + extra + """
                order by r.created_at desc, r.id desc
                limit :limit offset :offset
                """, queryParams, (rs, n) -> runRow(rs));
    }

    private List<Map<String, Object>> queryApprovals(MapSqlParameterSource params, MetricsCalculator.Window window, String role) {
        if (!"REVIEWER".equals(role) && !"ADMIN".equals(role)) {
            return List.of();
        }
        return jdbc.query("""
                select a.id, a.status, a.requested_at, a.decided_at, a.run_id, p.tool_name
                from approvals a
                join tool_proposals p on p.id = a.proposal_id
                where a.workspace_id = :workspace
                """ + window.sqlPredicate("a.requested_at") + """
                order by a.requested_at desc, a.id desc
                limit :limit
                """, new MapSqlParameterSource(params.getValues()).addValue("limit", APPROVAL_LIMIT), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("status", rs.getString("status"));
            row.put("requestedAt", rs.getTimestamp("requested_at").toInstant().toString());
            row.put("decidedAt", rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant().toString());
            row.put("runId", UUID.fromString(rs.getString("run_id")));
            row.put("tool", rs.getString("tool_name"));
            return row;
        });
    }

    private List<Map<String, Object>> queryActivity(MapSqlParameterSource params, String visibility, MetricsCalculator.Window window) {
        return jdbc.query("""
                select e.event_type, e.state, e.created_at, r.id as run_id, a.name as agent_name
                from run_events e
                join agent_runs r on r.id = e.run_id
                join agents a on a.id = r.agent_id
                where r.workspace_id = :workspace
                """ + visibility + window.sqlPredicate("e.created_at") + """
                order by e.created_at desc, e.sequence desc
                limit :limit
                """, new MapSqlParameterSource(params.getValues()).addValue("limit", ACTIVITY_LIMIT), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("eventType", rs.getString("event_type"));
            row.put("state", rs.getString("state"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("runId", UUID.fromString(rs.getString("run_id")));
            row.put("agentName", rs.getString("agent_name"));
            return row;
        });
    }

    private List<Map<String, Object>> queryAgents(MapSqlParameterSource params, String visibility, MetricsCalculator.Window window) {
        return jdbc.query("""
                select a.id, a.name, a.status, av.version_number,
                       count(r.id) as runs,
                       count(*) filter (where r.state = 'COMPLETED') as completed,
                       count(*) filter (where r.state in ('FAILED', 'TIMED_OUT')) as unsuccessful,
                       max(r.created_at) as last_run_at
                from agents a
                left join agent_versions av on av.agent_id = a.id and av.current_version
                left join agent_runs r on r.agent_id = a.id
                    and r.workspace_id = :workspace
                    """ + visJoin(visibility) + window.sqlPredicate("r.created_at") + """
                where a.workspace_id = :workspace
                group by a.id, a.name, a.status, av.version_number
                order by a.name
                """, params, (rs, n) -> {
            long runs = rs.getLong("runs");
            long completed = rs.getLong("completed");
            long unsuccessful = rs.getLong("unsuccessful");
            long terminal = completed + unsuccessful;
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("name", rs.getString("name"));
            row.put("status", rs.getString("status"));
            row.put("version", rs.getObject("version_number") == null ? null : rs.getInt("version_number"));
            row.put("runs", runs);
            row.put("completed", completed);
            row.put("unsuccessful", unsuccessful);
            row.put("lastRunAt", rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant().toString());
            row.put("completion", terminal == 0 ? null : (double) completed / terminal);
            return row;
        });
    }

    private List<Map<String, Object>> activityTrend(MapSqlParameterSource params, String visibility, MetricsCalculator.Window window) {
        if (window.unbounded()) {
            Instant oldest = jdbc.queryForObject(
                    "select min(created_at) from agent_runs r where r.workspace_id = :workspace" + visibility,
                    params, Instant.class);
            if (oldest == null) {
                return List.of();
            }
            Instant end = window.end();
            long days = Math.max(1, ChronoUnit.DAYS.between(oldest.truncatedTo(ChronoUnit.DAYS), end.truncatedTo(ChronoUnit.DAYS)) + 1);
            if (days > 30) {
                oldest = end.minus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
            }
            var range = new MapSqlParameterSource(params.getValues())
                    .addValue("windowStart", Timestamp.from(oldest.truncatedTo(ChronoUnit.DAYS)))
                    .addValue("windowEnd", Timestamp.from(end));
            return jdbc.query("""
                    select date_trunc('day', r.created_at) as bucket,
                           count(*) as runs,
                           count(*) filter (where r.state = 'COMPLETED') as completed,
                           count(*) filter (where r.state in ('FAILED', 'TIMED_OUT')) as unsuccessful
                    from agent_runs r
                    where r.workspace_id = :workspace
                    """ + visibility + """
                     and r.created_at >= :windowStart and r.created_at < :windowEnd
                    group by 1
                    order by 1
                    """, range, (rs, n) -> trendRow(rs));
        }
        String trunc = window.hours() != null && window.hours() <= 24 ? "hour" : "day";
        return jdbc.query(
                "select date_trunc('" + trunc + """
                ', r.created_at) as bucket,
                       count(*) as runs,
                       count(*) filter (where r.state = 'COMPLETED') as completed,
                       count(*) filter (where r.state in ('FAILED', 'TIMED_OUT')) as unsuccessful
                from agent_runs r
                where r.workspace_id = :workspace
                """ + visibility + window.sqlPredicate("r.created_at") + """
                group by 1
                order by 1
                """, params, (rs, n) -> trendRow(rs));
    }

    private List<Map<String, Object>> latencyTrend(MapSqlParameterSource params, String visibility, MetricsCalculator.Window window) {
        String trunc = !window.unbounded() && window.hours() != null && window.hours() <= 24 ? "hour" : "day";
        List<Map<String, Object>> points = jdbc.query(
                "select date_trunc('" + trunc + """
                ', r.ended_at) as bucket,
                       percentile_cont(0.50) within group (order by extract(epoch from (r.ended_at - r.started_at)) * 1000) as p50,
                       percentile_cont(0.95) within group (order by extract(epoch from (r.ended_at - r.started_at)) * 1000) as p95,
                       percentile_cont(0.99) within group (order by extract(epoch from (r.ended_at - r.started_at)) * 1000) as p99,
                       count(*) as samples
                from agent_runs r
                where r.workspace_id = :workspace
                  and r.state in ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')
                  and r.started_at is not null and r.ended_at is not null and r.ended_at >= r.started_at
                """ + visibility + (window.unbounded() ? "" : window.sqlPredicate("r.ended_at")) + """
                group by 1
                having count(*) >= 1
                order by 1
                """, params, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("bucket", rs.getTimestamp("bucket").toInstant().toString());
            row.put("p50Ms", rs.getObject("p50") == null ? null : rs.getDouble("p50"));
            row.put("p95Ms", rs.getObject("p95") == null ? null : rs.getDouble("p95"));
            row.put("p99Ms", rs.getObject("p99") == null ? null : rs.getDouble("p99"));
            row.put("samples", rs.getLong("samples"));
            return row;
        });
        if (points.size() < LATENCY_TREND_MIN_DAYS) {
            return List.of();
        }
        return points;
    }

    private static Map<String, Object> trendRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("bucket", rs.getTimestamp("bucket").toInstant().toString());
        row.put("runs", rs.getLong("runs"));
        row.put("completed", rs.getLong("completed"));
        row.put("unsuccessful", rs.getLong("unsuccessful"));
        return row;
    }

    private static Map<String, Object> runRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        UUID id = UUID.fromString(rs.getString("id"));
        row.put("id", id);
        row.put("state", rs.getString("state"));
        row.put("failureCategory", rs.getString("failure_category"));
        row.put("estimatedCostUsd", rs.getBigDecimal("estimated_cost_usd"));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("startedAt", rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant().toString());
        row.put("endedAt", rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant().toString());
        Object duration = rs.getObject("duration_ms");
        row.put("durationMs", duration instanceof Number number ? number.doubleValue() : null);
        String question = rs.getString("question");
        row.put("question", question == null ? "" : (question.length() > 140 ? question.substring(0, 140) : question));
        row.put("userId", UUID.fromString(rs.getString("user_id")));
        row.put("agentId", UUID.fromString(rs.getString("agent_id")));
        row.put("agentName", rs.getString("agent_name"));
        row.put("href", "/runs/" + id);
        return row;
    }

    private static MapSqlParameterSource baseParams(UUID workspaceId, UUID userId, MetricsCalculator.Window window) {
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("user", userId);
        if (!window.unbounded()) {
            params.addValue("windowStart", Timestamp.from(window.start()));
            params.addValue("windowEnd", Timestamp.from(window.end()));
        }
        return params;
    }

    private static String visJoin(String visibility) {
        return visibility == null ? " " : visibility;
    }

    private static String activePredicate() {
        String in = String.join("','", RunStateMachine.TERMINAL);
        return " and r.state not in ('" + in + "')";
    }

    private static String recentPredicate(String runFilter) {
        if (runFilter == null || runFilter.isBlank() || "ALL".equalsIgnoreCase(runFilter)) {
            return "";
        }
        return switch (runFilter.trim().toUpperCase()) {
            case "COMPLETED" -> " and r.state = 'COMPLETED'";
            case "FAILED" -> " and r.state = 'FAILED'";
            case "TIMED_OUT" -> " and r.state = 'TIMED_OUT'";
            case "CANCELLED" -> " and r.state = 'CANCELLED'";
            case "ACTIVE" -> activePredicate();
            default -> throw new ApiException("INVALID_FILTER", "Use ALL, COMPLETED, FAILED, TIMED_OUT, CANCELLED, or ACTIVE.", 400);
        };
    }
}
