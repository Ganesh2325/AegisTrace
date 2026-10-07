package com.aegistrace.audit;

import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.metrics.MetricsCalculator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditQueryService {
    static final int PAGE_SIZE = 25;
    static final int MAX_FILTER_LENGTH = 200;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditQueryService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> list(UUID workspaceId, String windowCode, String action, String actor, String resourceType,
                                    String result, String runId, String approvalId, String q, int page) {
        validateFilters(action, actor, resourceType, result, runId, approvalId, q);
        MetricsCalculator.Window window = parseWindow(windowCode);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("workspace", workspaceId);
        StringBuilder where = new StringBuilder(" where e.workspace_id = :workspace ");
        if (!window.unbounded()) {
            where.append(" and e.created_at >= :windowStart and e.created_at < :windowEnd ");
            params.addValue("windowStart", Timestamp.from(window.start()));
            params.addValue("windowEnd", Timestamp.from(window.end()));
        }
        if (notBlank(action)) {
            where.append(" and e.action = :action ");
            params.addValue("action", action.trim());
        }
        if (notBlank(resourceType)) {
            where.append(" and e.resource_type = :resourceType ");
            params.addValue("resourceType", resourceType.trim());
        }
        if (notBlank(runId)) {
            where.append(" and e.run_id = :runId ");
            try {
                params.addValue("runId", UUID.fromString(runId.trim()));
            } catch (IllegalArgumentException ex) {
                throw new ApiException("VALIDATION_FAILED", "runId must be a UUID.", 400);
            }
        }
        if (notBlank(approvalId)) {
            where.append(" and ((e.resource_type = 'approval' and e.resource_id = :approvalId) or e.metadata->>'approvalId' = :approvalId) ");
            params.addValue("approvalId", approvalId.trim());
        }
        if (notBlank(actor)) {
            where.append(" and (u.email ilike :actor or e.actor_id::text = :actorExact) ");
            params.addValue("actor", "%" + actor.trim() + "%");
            params.addValue("actorExact", actor.trim());
        }
        if (notBlank(result)) {
            where.append(" and ").append(resultSql()).append(" = :result ");
            params.addValue("result", result.trim().toUpperCase());
        }
        if (notBlank(q)) {
            String query = q.trim();
            where.append("""
                     and (
                       e.id::text = :q
                       or e.run_id::text = :q
                       or e.resource_id = :q
                       or e.action = :q
                       or e.trace_id = :q
                       or u.email ilike :qLike
                       or e.metadata->>'proposalId' = :q
                       or e.metadata->>'approvalId' = :q
                     )
                    """);
            params.addValue("q", query);
            params.addValue("qLike", "%" + query + "%");
        }
        int safePage = Math.max(page, 0);
        params.addValue("limit", PAGE_SIZE);
        params.addValue("offset", safePage * PAGE_SIZE);
        String from = """
                from audit_events e
                left join users u on u.id = e.actor_id
                left join memberships m on m.user_id = e.actor_id and m.workspace_id = e.workspace_id
                """ + where;
        Long total = jdbc.queryForObject("select count(*) " + from, params, Long.class);
        var items = jdbc.query("""
                select e.id, e.actor_id, u.email as actor_email, m.role as actor_role, e.action, e.resource_type,
                       e.resource_id, e.trace_id, e.run_id, e.metadata::text as metadata, e.created_at, e.workspace_id
                """ + from + " order by e.created_at desc limit :limit offset :offset", params, (rs, n) -> summary(rs, false));
        var counts = jdbc.queryForMap("""
                select
                  count(*) filter (where e.created_at >= date_trunc('day', now()) at time zone 'utc') as today,
                  count(*) filter (where e.action like 'APPROVAL_%') as approvals,
                  count(*) filter (where e.action in ('AGENT_CREATED','AGENT_VERSION_CREATED','AGENT_VERSION_ACTIVATED','AGENT_STATUS_CHANGED','PROMPT_VERSION_CREATED','KNOWLEDGE_BASE_CREATED','KNOWLEDGE_VERSION_CREATED','KNOWLEDGE_VERSION_ACTIVATED','SETTINGS_CHANGED')) as configuration,
                  count(*) filter (where e.action = 'POLICY_DECISION') as policy
                """ + from, params);
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("page", safePage);
        body.put("size", PAGE_SIZE);
        body.put("total", total == null ? 0 : total);
        body.put("window", window.code());
        body.put("summary", Map.of(
                "today", number(counts.get("today")),
                "approvalEvents", number(counts.get("approvals")),
                "configurationChanges", number(counts.get("configuration")),
                "policyDecisions", number(counts.get("policy"))
        ));
        return body;
    }

    public Map<String, Object> get(UUID workspaceId, UUID id) {
        var rows = jdbc.query("""
                select e.id, e.actor_id, u.email as actor_email, m.role as actor_role, e.action, e.resource_type,
                       e.resource_id, e.trace_id, e.run_id, e.request_id, e.metadata::text as metadata, e.created_at,
                       e.workspace_id
                from audit_events e
                left join users u on u.id = e.actor_id
                left join memberships m on m.user_id = e.actor_id and m.workspace_id = e.workspace_id
                where e.workspace_id = :workspace and e.id = :id
                """, Map.of("workspace", workspaceId, "id", id), (rs, n) -> summary(rs, true));
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Audit event not found.", 404);
        }
        return rows.get(0);
    }

    private Map<String, Object> summary(java.sql.ResultSet rs, boolean detail) throws java.sql.SQLException {
        Map<String, Object> metadata = Jsons.map(mapper, rs.getString("metadata"));
        Map<String, Object> safe = AuditRecords.sanitize(metadata);
        UUID actorId = rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id"));
        String action = rs.getString("action");
        String resourceType = rs.getString("resource_type");
        String resourceId = rs.getString("resource_id");
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.fromString(rs.getString("id")));
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("actorId", actorId);
        row.put("actorEmail", rs.getString("actor_email"));
        row.put("actorKind", AuditRecords.actorKind(actorId));
        row.put("role", rs.getString("actor_role"));
        row.put("action", action);
        row.put("resourceType", resourceType);
        row.put("resourceId", resourceId);
        row.put("result", AuditRecords.result(action, metadata));
        row.put("runId", rs.getString("run_id") == null ? null : UUID.fromString(rs.getString("run_id")));
        row.put("traceId", rs.getString("trace_id"));
        row.put("workspaceId", rs.getString("workspace_id") == null ? null : UUID.fromString(rs.getString("workspace_id")));
        row.put("approvalId", AuditRecords.approvalId(resourceType, resourceId, metadata));
        if (detail) {
            row.put("requestId", rs.getString("request_id"));
            row.put("metadata", safe);
            row.put("reason", safe.get("reason"));
        }
        return row;
    }

    private static String resultSql() {
        return """
                case
                  when e.action = 'LOGIN_FAILED' then 'FAILED'
                  when e.action = 'POLICY_DECISION' and e.metadata->>'decision' = 'DENY' then 'DENIED'
                  else 'SUCCESS'
                end
                """;
    }

    private static MetricsCalculator.Window parseWindow(String raw) {
        try {
            if (raw == null || raw.isBlank()) {
                return MetricsCalculator.Window.parse("7D", Instant.now());
            }
            return MetricsCalculator.Window.parse(raw, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("VALIDATION_FAILED", "Unsupported window.", 400);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static void validateFilters(String... values) {
        for (String value : values) {
            if (value != null && value.length() > MAX_FILTER_LENGTH) {
                throw new ApiException("VALIDATION_ERROR", "Audit filters are limited to 200 characters.", 400);
            }
        }
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
