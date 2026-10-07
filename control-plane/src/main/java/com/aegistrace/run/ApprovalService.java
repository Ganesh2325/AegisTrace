package com.aegistrace.run;

import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.security.Actor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ApprovalService {
    private static final Set<String> STATUSES = Set.of("PENDING", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED");
    private static final Set<String> RISKS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 50;
    private static final int MAX_FILTER_LENGTH = 200;
    private static final int TIMELINE_LIMIT = 100;

    private static final String FROM = """
            from approvals a
            join tool_proposals p on p.id = a.proposal_id
            join agent_runs r on r.id = a.run_id
            join users requester on requester.id = a.requester_id
            left join users reviewer on reviewer.id = a.reviewer_id
            join agents ag on ag.id = r.agent_id
            join agent_versions av on av.id = r.agent_version_id
            left join knowledge_bases kb on kb.id = r.knowledge_base_id
            left join knowledge_base_versions kv on kv.id = r.knowledge_base_version_id
            left join tools t on t.name = p.tool_name
            left join tool_executions te on te.proposal_id = a.proposal_id
            left join tickets tk on tk.proposal_id = a.proposal_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ApprovalService(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public Map<String, Object> list(Actor actor, UUID workspaceId, String status, String risk, String q,
                                    String requester, String agent, int page, int size) {
        requireRead(actor, workspaceId);
        validateFilter(q);
        validateFilter(requester);
        validateFilter(agent);
        int limit = Math.min(Math.max(size, 1), MAX_SIZE);
        if (size <= 0) {
            limit = DEFAULT_SIZE;
        }
        int offset = Math.max(page, 0) * limit;
        MapSqlParameterSource params = baseParams(workspaceId, status, risk, q, requester, agent)
                .addValue("limit", limit)
                .addValue("offset", offset);
        String where = whereSql(status, risk, q, requester, agent);
        String order = orderSql(status);
        List<Map<String, Object>> items = jdbc.query("""
                select a.id, a.status, a.required_role, a.requested_at, a.decided_at, a.expires_at,
                       a.run_id, a.proposal_id, a.requester_id, a.reviewer_id,
                       p.tool_name, p.risk_level, p.policy_decision, p.reason as proposal_reason,
                       requester.email as requester_email,
                       ag.name as agent_name, av.version_number as agent_version,
                       t.classification
                """ + FROM + """
                where a.workspace_id = :workspace
                  and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
                """ + where + order + """
                limit :limit offset :offset
                """, params, (rs, n) -> listItem(rs, actor, workspaceId));
        Long total = jdbc.queryForObject(
                "select count(*) " + FROM + """
                 where a.workspace_id = :workspace
                   and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
                """ + where, params, Long.class);
        var body = new LinkedHashMap<String, Object>();
        body.put("items", items);
        body.put("page", Math.max(page, 0));
        body.put("size", limit);
        body.put("total", total == null ? 0 : total);
        body.put("summary", summary(workspaceId));
        body.put("ordering", "PENDING first by risk (CRITICAL, HIGH, MEDIUM, LOW), then expires_at, then requested_at. History by decided_at then requested_at.");
        return body;
    }

    public Map<String, Object> summary(UUID workspaceId) {
        return jdbc.queryForObject("""
                select
                    count(*) filter (where status = 'PENDING') as pending,
                    count(*) filter (where status = 'APPROVED' and decided_at >= date_trunc('day', now())) as approved_today,
                    count(*) filter (where status = 'REJECTED' and decided_at >= date_trunc('day', now())) as rejected_today,
                    count(*) filter (where status = 'EXPIRED') as expired,
                    count(*) filter (where status = 'CANCELLED') as cancelled
                from approvals
                where workspace_id = :workspace
                  and not exists (
                    select 1 from evaluation_results er_scope
                    where er_scope.product_run_id = approvals.run_id
                  )
                """, Map.of("workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("pending", rs.getLong("pending"));
            row.put("approvedToday", rs.getLong("approved_today"));
            row.put("rejectedToday", rs.getLong("rejected_today"));
            row.put("expired", rs.getLong("expired"));
            row.put("cancelled", rs.getLong("cancelled"));
            return row;
        });
    }

    public Map<String, Object> get(Actor actor, UUID workspaceId, UUID approvalId) {
        requireRead(actor, workspaceId);
        var rows = jdbc.query("""
                select a.id, a.status, a.required_role, a.decision_reason, a.requested_at, a.decided_at, a.expires_at,
                       a.run_id, a.proposal_id, a.requester_id, a.reviewer_id, a.workspace_id,
                       p.tool_name, p.arguments::text as arguments, p.reason as proposal_reason, p.risk_level,
                       p.policy_decision, p.policy_code, p.policy_reason,
                       r.state as run_state, r.trace_id, r.citations::text as citations, r.agent_version_id,
                       r.knowledge_base_version_id,
                       requester.email as requester_email,
                       reviewer.email as reviewer_email,
                       ag.id as agent_id, ag.name as agent_name, av.version_number as agent_version,
                       kb.name as knowledge_name, kv.version_number as knowledge_version,
                       t.classification,
                       te.status as execution_status, te.error_type as execution_error,
                       tk.id as ticket_id
                """ + FROM + """
                where a.id = :id and a.workspace_id = :workspace
                  and not exists (select 1 from evaluation_results er_scope where er_scope.product_run_id = r.id)
                """, Map.of("id", approvalId, "workspace", workspaceId), (rs, n) -> detail(rs, actor, workspaceId));
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Approval not found.", 404);
        }
        Map<String, Object> body = rows.get(0);
        UUID runId = (UUID) body.get("runId");
        body.put("timeline", timeline(runId));
        return body;
    }

    private void requireRead(Actor actor, UUID workspaceId) {
        var membership = actor.membership(workspaceId);
        if (membership == null || !ApprovalAccess.canRead(membership.role())) {
            throw new ApiException("FORBIDDEN", "Your role cannot read approvals.", 403);
        }
    }

    private MapSqlParameterSource baseParams(UUID workspaceId, String status, String risk, String q,
                                             String requester, String agent) {
        return new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("status", blank(status) ? null : status.trim().toUpperCase())
                .addValue("risk", blank(risk) ? null : risk.trim().toUpperCase())
                .addValue("q", blank(q) ? null : "%" + q.trim() + "%")
                .addValue("qExact", blank(q) ? null : q.trim())
                .addValue("requester", blank(requester) ? null : "%" + requester.trim() + "%")
                .addValue("agent", blank(agent) ? null : "%" + agent.trim() + "%");
    }

    private String whereSql(String status, String risk, String q, String requester, String agent) {
        StringBuilder sql = new StringBuilder();
        if (!blank(status)) {
            String normalized = status.trim().toUpperCase();
            if (!STATUSES.contains(normalized)) {
                throw new ApiException("VALIDATION_ERROR", "Unknown approval status.", 400);
            }
            sql.append(" and a.status = :status ");
        }
        if (!blank(risk)) {
            String normalized = risk.trim().toUpperCase();
            if (!RISKS.contains(normalized)) {
                throw new ApiException("VALIDATION_ERROR", "Unknown risk level.", 400);
            }
            sql.append(" and p.risk_level = :risk ");
        }
        if (!blank(q)) {
            sql.append("""
                     and (
                        p.tool_name ilike :q
                        or requester.email ilike :q
                        or ag.name ilike :q
                        or a.id::text = :qExact
                        or a.run_id::text = :qExact
                     )
                    """);
        }
        if (!blank(requester)) {
            sql.append(" and requester.email ilike :requester ");
        }
        if (!blank(agent)) {
            sql.append(" and ag.name ilike :agent ");
        }
        return sql.toString();
    }

    private String orderSql(String status) {
        if ("PENDING".equalsIgnoreCase(blank(status) ? "" : status.trim())) {
            return """
                    order by case p.risk_level
                        when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
                        a.expires_at asc, a.requested_at asc, a.id asc
                    """;
        }
        return """
                order by case a.status when 'PENDING' then 0 else 1 end,
                    case p.risk_level
                        when 'CRITICAL' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 when 'LOW' then 3 else 4 end,
                    case when a.status = 'PENDING' then a.expires_at end asc,
                    coalesce(a.decided_at, a.requested_at) desc, a.id desc
                """;
    }

    private Map<String, Object> listItem(java.sql.ResultSet rs, Actor actor, UUID workspaceId) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        UUID id = UUID.fromString(rs.getString("id"));
        UUID requesterId = UUID.fromString(rs.getString("requester_id"));
        var membership = actor.membership(workspaceId);
        String role = membership == null ? "" : membership.role();
        row.put("id", id);
        row.put("status", rs.getString("status"));
        row.put("tool", rs.getString("tool_name"));
        row.put("classification", rs.getString("classification"));
        row.put("risk", rs.getString("risk_level"));
        row.put("policyDecision", rs.getString("policy_decision"));
        row.put("proposalReason", rs.getString("proposal_reason"));
        row.put("requiredRole", rs.getString("required_role"));
        row.put("requestedAt", rs.getTimestamp("requested_at").toInstant().toString());
        row.put("decidedAt", rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant().toString());
        row.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
        row.put("runId", UUID.fromString(rs.getString("run_id")));
        row.put("proposalId", UUID.fromString(rs.getString("proposal_id")));
        row.put("requesterId", requesterId);
        row.put("requesterEmail", rs.getString("requester_email"));
        row.put("reviewerId", rs.getString("reviewer_id") == null ? null : UUID.fromString(rs.getString("reviewer_id")));
        row.put("agentName", rs.getString("agent_name"));
        row.put("agentVersion", rs.getObject("agent_version") == null ? null : rs.getInt("agent_version"));
        row.put("selfRequested", actor.id().equals(requesterId));
        row.put("canDecide", ApprovalAccess.canDecideThis(role, actor.id(), requesterId)
                && "PENDING".equals(rs.getString("status"))
                && (!"ADMIN".equals(rs.getString("required_role")) || "ADMIN".equals(role)));
        return row;
    }

    private Map<String, Object> detail(java.sql.ResultSet rs, Actor actor, UUID workspaceId) throws java.sql.SQLException {
        Map<String, Object> row = listItem(rs, actor, workspaceId);
        var membership = actor.membership(workspaceId);
        String role = membership == null ? "" : membership.role();
        boolean includeArguments = RunVisibility.includeProposalArguments(role);
        row.put("decisionReason", rs.getString("decision_reason"));
        row.put("reviewerEmail", rs.getString("reviewer_email"));
        row.put("policyCode", rs.getString("policy_code"));
        row.put("policyReason", rs.getString("policy_reason"));
        row.put("runState", rs.getString("run_state"));
        row.put("traceId", rs.getString("trace_id"));
        row.put("agentId", UUID.fromString(rs.getString("agent_id")));
        row.put("agentVersionId", UUID.fromString(rs.getString("agent_version_id")));
        row.put("knowledgeName", rs.getString("knowledge_name"));
        row.put("knowledgeVersion", rs.getObject("knowledge_version") == null ? null : rs.getInt("knowledge_version"));
        row.put("knowledgeBaseVersionId", rs.getString("knowledge_base_version_id") == null
                ? null : UUID.fromString(rs.getString("knowledge_base_version_id")));
        row.put("arguments", includeArguments ? Jsons.map(mapper, rs.getString("arguments")) : null);
        row.put("includeArguments", includeArguments);
        row.put("citations", citations(rs.getString("citations")));
        row.put("executionStatus", rs.getString("execution_status"));
        row.put("executionError", rs.getString("execution_error"));
        row.put("ticketId", rs.getString("ticket_id") == null ? null : UUID.fromString(rs.getString("ticket_id")));
        row.put("whatWillHappen", whatWillHappen(rs.getString("tool_name"), rs.getString("status"),
                rs.getString("execution_status"), rs.getString("ticket_id")));
        return row;
    }

    private List<Map<String, Object>> citations(String json) {
        List<Map<String, Object>> raw;
        try {
            raw = mapper.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
        if (raw == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> item : raw) {
            var row = new LinkedHashMap<String, Object>();
            row.put("documentTitle", item.get("documentTitle"));
            row.put("section", item.get("section"));
            row.put("quote", item.get("quote"));
            row.put("score", item.get("score"));
            row.put("chunkId", item.get("chunkId"));
            out.add(row);
        }
        return out;
    }

    private List<Map<String, Object>> timeline(UUID runId) {
        return jdbc.query("""
                select sequence, event_type, state, payload, created_at from (
                    select sequence, event_type, state, payload::text as payload, created_at
                    from run_events
                    where run_id = :id
                      and event_type in (
                        'TOOL_PROPOSED', 'POLICY_DECIDED', 'APPROVAL_REQUIRED', 'APPROVAL_APPROVED',
                        'APPROVAL_REJECTED', 'APPROVAL_EXPIRED', 'APPROVAL_CANCELLED',
                        'TOOL_STARTED', 'TOOL_COMPLETED', 'RUN_COMPLETED', 'RUN_FAILED',
                        'RUN_CANCELLED', 'RUN_TIMED_OUT'
                      )
                    order by sequence desc limit :limit
                ) history order by sequence
                """, Map.of("id", runId, "limit", TIMELINE_LIMIT), (rs, n) -> Map.of(
                "sequence", rs.getInt("sequence"),
                "eventType", rs.getString("event_type"),
                "state", rs.getString("state"),
                "payload", Jsons.map(mapper, rs.getString("payload")),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString()
        ));
    }

    private String whatWillHappen(String tool, String status, String executionStatus, String ticketId) {
        if ("create_support_ticket".equals(tool)) {
            if (ticketId != null && "SUCCEEDED".equals(executionStatus)) {
                return "A support ticket was created once for this proposal.";
            }
            if ("REJECTED".equals(status) || "EXPIRED".equals(status) || "CANCELLED".equals(status)) {
                return "The queued support-ticket action will not execute.";
            }
            if ("APPROVED".equals(status) && executionStatus == null) {
                return "The queued support-ticket action is allowed to continue if the run remains executable.";
            }
            if ("SKIPPED".equals(executionStatus)) {
                return "The worker skipped ticket creation. No ticket was written.";
            }
            return "If approved, the worker will create one support ticket for this proposal.";
        }
        return "If approved, the queued tool action may execute once for this proposal.";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void validateFilter(String value) {
        if (value != null && value.length() > MAX_FILTER_LENGTH) {
            throw new ApiException("VALIDATION_ERROR", "Approval filters are limited to 200 characters.", 400);
        }
    }
}
