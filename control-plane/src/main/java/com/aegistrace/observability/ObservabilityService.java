package com.aegistrace.observability;

import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.config.AppProperties;
import com.aegistrace.metrics.MetricsCalculator;
import com.aegistrace.metrics.MetricsService;
import com.aegistrace.run.RunService;
import com.aegistrace.run.RunVisibility;
import com.aegistrace.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ObservabilityService {
    static final int PAGE_SIZE = 25;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MetricsService metrics;
    private final JaegerClient jaeger;
    private final AppProperties properties;

    public ObservabilityService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper, MetricsService metrics,
                                JaegerClient jaeger, AppProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.metrics = metrics;
        this.jaeger = jaeger;
        this.properties = properties;
    }

    public Map<String, Object> overview(Actor actor, UUID workspaceId, String windowCode) {
        MetricsCalculator.Window window = parseWindow(windowCode);
        String visibility = RunService.visibilitySql(actor.membership(workspaceId).role());
        Map<String, Object> summary = metrics.summary(workspaceId, window, visibility, actor.id());
        MapSqlParameterSource params = windowParams(workspaceId, actor.id(), window);
        String vis = visibility;
        Long tools = jdbc.queryForObject("""
                select count(*) from run_events e
                join agent_runs r on r.id = e.run_id
                where r.workspace_id = :workspace and e.event_type = 'TOOL_COMPLETED'
                """ + vis + window.sqlPredicate("e.created_at"), params, Long.class);
        var errors = jdbc.query("""
                select r.id, r.trace_id, r.state, r.failure_category, r.error_code, r.created_at, r.ended_at,
                       a.name as agent_name
                from agent_runs r
                join agents a on a.id = r.agent_id
                where r.workspace_id = :workspace and r.state in ('FAILED', 'TIMED_OUT')
                """ + vis + window.sqlPredicate("r.created_at") + """
                order by r.created_at desc limit 8
                """, params, (rs, n) -> errorRow(rs.getString("id"), rs.getString("trace_id"), rs.getString("state"),
                rs.getString("failure_category"), rs.getString("error_code"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("agent_name")));
        var traces = jdbc.query(traceSelect() + vis + window.sqlPredicate("r.created_at") + """
                order by r.created_at desc limit 8
                """, params, (rs, n) -> listRow(rs));
        var body = new LinkedHashMap<String, Object>();
        body.put("generatedAt", Instant.now().toString());
        body.put("window", summary.get("window"));
        body.put("windowHours", summary.get("windowHours"));
        body.put("windowStart", summary.get("windowStart"));
        body.put("windowEnd", summary.get("windowEnd"));
        body.put("timezone", "UTC");
        body.put("metrics", summary);
        body.put("metricsSource", "postgres");
        body.put("toolExecutions", Map.of(
                "value", tools == null ? 0 : tools,
                "status", "OK",
                "definition", "Count of TOOL_COMPLETED product events in the window for visible runs.",
                "unit", "count"));
        body.put("recentErrors", errors);
        body.put("recentTraces", traces);
        body.put("telemetry", telemetryStatus());
        body.put("services", services());
        body.put("environment", properties.getEnvironment());
        return body;
    }

    public Map<String, Object> traces(Actor actor, UUID workspaceId, String windowCode, String status, String runId,
                                      String traceId, String agent, int page) {
        MetricsCalculator.Window window = parseWindow(windowCode);
        String visibility = RunService.visibilitySql(actor.membership(workspaceId).role());
        MapSqlParameterSource params = windowParams(workspaceId, actor.id(), window);
        StringBuilder extra = new StringBuilder();
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            extra.append(" and r.state = :state ");
            params.addValue("state", status.trim().toUpperCase());
        }
        if (runId != null && !runId.isBlank()) {
            extra.append(" and r.id = :runId ");
            try {
                params.addValue("runId", UUID.fromString(runId.trim()));
            } catch (IllegalArgumentException ex) {
                throw new ApiException("VALIDATION_FAILED", "runId must be a UUID.", 400);
            }
        }
        if (traceId != null && !traceId.isBlank()) {
            extra.append(" and r.trace_id = :traceId ");
            params.addValue("traceId", traceId.trim());
        }
        if (agent != null && !agent.isBlank()) {
            extra.append(" and a.name ilike :agent ");
            params.addValue("agent", "%" + agent.trim() + "%");
        }
        int safePage = Math.max(page, 0);
        params.addValue("limit", PAGE_SIZE);
        params.addValue("offset", safePage * PAGE_SIZE);
        String where = visibility + window.sqlPredicate("r.created_at") + extra;
        Long total = jdbc.queryForObject("select count(*) from agent_runs r join agents a on a.id = r.agent_id where r.workspace_id = :workspace " + where,
                params, Long.class);
        var items = jdbc.query(traceSelect() + where + " order by r.created_at desc limit :limit offset :offset",
                params, (rs, n) -> listRow(rs));
        return Map.of(
                "items", items,
                "page", safePage,
                "size", PAGE_SIZE,
                "total", total == null ? 0 : total,
                "window", window.code(),
                "telemetry", telemetryStatus()
        );
    }

    public Map<String, Object> errors(Actor actor, UUID workspaceId, String windowCode, int page) {
        MetricsCalculator.Window window = parseWindow(windowCode);
        String visibility = RunService.visibilitySql(actor.membership(workspaceId).role());
        MapSqlParameterSource params = windowParams(workspaceId, actor.id(), window);
        int safePage = Math.max(page, 0);
        params.addValue("limit", PAGE_SIZE);
        params.addValue("offset", safePage * PAGE_SIZE);
        String where = visibility + window.sqlPredicate("r.created_at");
        Long total = jdbc.queryForObject("""
                select count(*) from agent_runs r
                where r.workspace_id = :workspace and r.state in ('FAILED', 'TIMED_OUT')
                """ + where, params, Long.class);
        var items = jdbc.query("""
                select r.id, r.trace_id, r.state, r.failure_category, r.error_code, r.created_at, a.name as agent_name
                from agent_runs r
                join agents a on a.id = r.agent_id
                where r.workspace_id = :workspace and r.state in ('FAILED', 'TIMED_OUT')
                """ + where + " order by r.created_at desc limit :limit offset :offset", params,
                (rs, n) -> errorRow(rs.getString("id"), rs.getString("trace_id"), rs.getString("state"),
                        rs.getString("failure_category"), rs.getString("error_code"),
                        rs.getTimestamp("created_at").toInstant(), rs.getString("agent_name")));
        var groups = jdbc.query("""
                select r.failure_category as code, count(*) as n
                from agent_runs r
                where r.workspace_id = :workspace and r.state in ('FAILED', 'TIMED_OUT') and r.failure_category is not null
                """ + where + " group by r.failure_category order by n desc limit 20", params,
                (rs, n) -> Map.of("code", rs.getString("code"), "count", rs.getLong("n")));
        return Map.of("items", items, "groups", groups, "page", safePage, "size", PAGE_SIZE,
                "total", total == null ? 0 : total, "window", window.code());
    }

    public Map<String, Object> trace(Actor actor, UUID workspaceId, String rawTraceId) {
        String traceId = rawTraceId == null ? "" : rawTraceId.trim();
        if (traceId.isBlank()) {
            throw new ApiException("NOT_FOUND", "Trace not found.", 404);
        }
        String visibility = RunService.visibilitySql(actor.membership(workspaceId).role());
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("user", actor.id())
                .addValue("traceId", traceId);
        var runs = jdbc.query("""
                select r.id, r.workspace_id, r.user_id, r.state, r.trace_id, r.created_at, r.started_at, r.ended_at,
                       r.failure_category, r.error_code, r.agent_id, r.agent_version_id, r.knowledge_base_id,
                       r.knowledge_base_version_id, r.provider, r.model,
                       a.name as agent_name, av.version_number as agent_version,
                       exists (select 1 from approvals vis where vis.run_id = r.id) as has_approval
                from agent_runs r
                join agents a on a.id = r.agent_id
                join agent_versions av on av.id = r.agent_version_id
                where r.workspace_id = :workspace and r.trace_id = :traceId
                """ + visibility + " order by r.created_at desc limit 1", params, (rs, n) -> new TraceRun(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("workspace_id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("state"),
                rs.getString("trace_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant(),
                rs.getString("failure_category"),
                rs.getString("error_code"),
                UUID.fromString(rs.getString("agent_id")),
                UUID.fromString(rs.getString("agent_version_id")),
                rs.getString("knowledge_base_id") == null ? null : UUID.fromString(rs.getString("knowledge_base_id")),
                rs.getString("knowledge_base_version_id") == null ? null : UUID.fromString(rs.getString("knowledge_base_version_id")),
                rs.getString("provider"),
                rs.getString("model"),
                rs.getString("agent_name"),
                rs.getInt("agent_version"),
                rs.getBoolean("has_approval")));
        if (runs.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Trace not found.", 404);
        }
        TraceRun run = runs.get(0);
        RunVisibility.assertVisible(actor.membership(workspaceId).role(), actor.id(), run.ownerId(), workspaceId,
                run.workspaceId(), run.hasApproval());
        Long durationMs = run.startedAt() == null || run.endedAt() == null
                ? null : Math.max(0, run.endedAt().toEpochMilli() - run.startedAt().toEpochMilli());
        var events = jdbc.query("""
                select event_type, state, payload::text as payload, created_at
                from run_events where run_id = :id order by sequence
                """, Map.of("id", run.id()), (row, n) -> new ProductPhases.Event(
                row.getString("event_type"),
                row.getTimestamp("created_at").toInstant(),
                Jsons.map(mapper, row.getString("payload")),
                row.getString("state")));
        var phases = ProductPhases.from(events);
        var productEvents = jdbc.query("""
                select sequence, event_type, state, created_at
                from run_events where run_id = :id order by sequence
                """, Map.of("id", run.id()), (row, n) -> Map.of(
                "kind", "PRODUCT_EVENT",
                "sequence", row.getInt("sequence"),
                "name", row.getString("event_type"),
                "state", row.getString("state"),
                "at", row.getTimestamp("created_at").toInstant().toString()));
        Integer knowledgeVersion = null;
        if (run.knowledgeVersionId() != null) {
            knowledgeVersion = jdbc.queryForObject(
                    "select version_number from knowledge_base_versions where id = :id",
                    Map.of("id", run.knowledgeVersionId()), Integer.class);
        }
        UUID approvalId = jdbc.query("select id from approvals where run_id = :id order by requested_at desc limit 1",
                Map.of("id", run.id()), (row, n) -> UUID.fromString(row.getString("id")))
                .stream().findFirst().orElse(null);
        UUID proposalId = jdbc.query("select id from tool_proposals where run_id = :id order by created_at desc limit 1",
                Map.of("id", run.id()), (row, n) -> UUID.fromString(row.getString("id")))
                .stream().findFirst().orElse(null);
        JaegerClient.TraceFetch fetch = jaeger.fetch(traceId);
        var body = new LinkedHashMap<String, Object>();
        body.put("traceId", run.traceId());
        body.put("runId", run.id());
        body.put("status", traceStatus(run.state(), fetch.status()));
        body.put("runState", run.state());
        body.put("failureCategory", run.failureCategory());
        body.put("errorCode", run.errorCode());
        body.put("durationMs", durationMs);
        body.put("createdAt", run.createdAt().toString());
        body.put("startedAt", run.startedAt() == null ? null : run.startedAt().toString());
        body.put("endedAt", run.endedAt() == null ? null : run.endedAt().toString());
        body.put("environment", properties.getEnvironment());
        body.put("agentId", run.agentId());
        body.put("agentName", run.agentName());
        body.put("agentVersion", run.agentVersion());
        body.put("agentVersionId", run.agentVersionId());
        body.put("knowledgeBaseId", run.knowledgeBaseId());
        body.put("knowledgeVersionId", run.knowledgeVersionId());
        body.put("knowledgeVersion", knowledgeVersion);
        body.put("provider", run.provider());
        body.put("model", run.model());
        body.put("approvalId", approvalId);
        body.put("toolProposalId", proposalId);
        body.put("productPhases", phases.stream().map(ProductPhases.Phase::toMap).toList());
        body.put("phaseTotalsMs", ProductPhases.sums(phases));
        body.put("productEvents", productEvents);
        body.put("telemetry", Map.of(
                "status", fetch.status(),
                "message", fetch.message() == null ? "" : fetch.message(),
                "spanCount", fetch.spans().size()));
        body.put("spans", fetch.spans());
        body.put("jaegerUrl", TelemetryPrivacy.jaegerTraceUrl(properties.getJaegerPublicUrl(), traceId));
        body.put("grafanaUrl", grafanaUrl());
        return body;
    }

    private record TraceRun(UUID id, UUID workspaceId, UUID ownerId, String state, String traceId, Instant createdAt,
                            Instant startedAt, Instant endedAt, String failureCategory, String errorCode,
                            UUID agentId, UUID agentVersionId, UUID knowledgeBaseId, UUID knowledgeVersionId,
                            String provider, String model, String agentName, int agentVersion, boolean hasApproval) {}

    private Map<String, Object> telemetryStatus() {
        String prometheus = jaeger.prometheusStatus();
        String traces = jaeger.tracesBackendStatus();
        var body = new LinkedHashMap<String, Object>();
        body.put("traces", traces);
        body.put("metricsSql", "OK");
        body.put("prometheus", prometheus);
        body.put("samplingProbability", 1.0);
        body.put("jaegerUrl", blankToNull(properties.getJaegerPublicUrl()));
        body.put("grafanaUrl", grafanaUrl());
        return body;
    }

    private List<Map<String, Object>> services() {
        return List.of(
                Map.of("name", "Control plane", "telemetry", "OpenTelemetry traces and Prometheus scrape of /actuator/prometheus"),
                Map.of("name", "AI runtime", "telemetry", "No OpenTelemetry export. Correlated by stored run_id and trace_id on the plan request."),
                Map.of("name", "Worker", "telemetry", "No OpenTelemetry export. Correlated by job payload run_id, proposal_id, and trace_id."),
                Map.of("name", "PostgreSQL", "telemetry", "Product events and audit events. Not a trace store."),
                Map.of("name", "Redis", "telemetry", "Not used as a metrics source in this view.")
        );
    }

    private Map<String, Object> listRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant started = rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant();
        Instant ended = rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant();
        var row = new LinkedHashMap<String, Object>();
        row.put("traceId", rs.getString("trace_id"));
        row.put("runId", UUID.fromString(rs.getString("id")));
        row.put("operation", "aegistrace.agent.run");
        row.put("status", listStatus(rs.getString("state")));
        row.put("runState", rs.getString("state"));
        row.put("durationMs", started == null || ended == null ? null : Math.max(0, ended.toEpochMilli() - started.toEpochMilli()));
        row.put("agent", rs.getString("agent_name"));
        row.put("agentVersion", rs.getObject("agent_version") == null ? null : rs.getInt("agent_version"));
        row.put("startedAt", started == null ? rs.getTimestamp("created_at").toInstant().toString() : started.toString());
        row.put("environment", properties.getEnvironment());
        return row;
    }

    private static Map<String, Object> errorRow(String runId, String traceId, String state, String category,
                                                String code, Instant at, String agent) {
        var row = new LinkedHashMap<String, Object>();
        row.put("runId", UUID.fromString(runId));
        row.put("traceId", traceId);
        row.put("service", "control-plane");
        row.put("operation", "aegistrace.agent.run");
        row.put("category", category);
        row.put("code", code);
        row.put("state", state);
        row.put("at", at.toString());
        row.put("agent", agent);
        return row;
    }

    private static String listStatus(String state) {
        if ("FAILED".equals(state) || "TIMED_OUT".equals(state)) {
            return "ERROR";
        }
        if ("COMPLETED".equals(state) || "CANCELLED".equals(state)) {
            return "OK";
        }
        return "UNKNOWN";
    }

    private static String traceStatus(String runState, String telemetry) {
        if ("UNAVAILABLE".equals(telemetry) || "NOT_CONFIGURED".equals(telemetry)) {
            if ("FAILED".equals(runState) || "TIMED_OUT".equals(runState)) {
                return "ERROR";
            }
            return "UNAVAILABLE".equals(telemetry) ? "UNAVAILABLE" : listStatus(runState);
        }
        return listStatus(runState);
    }

    private static String traceSelect() {
        return """
                select r.id, r.trace_id, r.state, r.created_at, r.started_at, r.ended_at, a.name as agent_name,
                       av.version_number as agent_version
                from agent_runs r
                join agents a on a.id = r.agent_id
                join agent_versions av on av.id = r.agent_version_id
                where r.workspace_id = :workspace
                """;
    }

    private static MapSqlParameterSource windowParams(UUID workspaceId, UUID userId, MetricsCalculator.Window window) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("user", userId);
        if (!window.unbounded()) {
            params.addValue("windowStart", Timestamp.from(window.start()));
            params.addValue("windowEnd", Timestamp.from(window.end()));
        }
        return params;
    }

    private static MetricsCalculator.Window parseWindow(String raw) {
        try {
            if (raw == null || raw.isBlank() || "ALL".equalsIgnoreCase(raw)) {
                return MetricsCalculator.Window.parse("24H", Instant.now());
            }
            return MetricsCalculator.Window.parse(raw, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("VALIDATION_FAILED", "Unsupported window.", 400);
        }
    }

    private String grafanaUrl() {
        return blankToNull(System.getenv().getOrDefault("GRAFANA_PUBLIC_URL", "http://localhost:3001"));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
