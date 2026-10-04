package com.aegistrace.agent;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.security.Rbac;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class AgentController {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final Rbac rbac;

    AgentController(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, AuditService audit, Rbac rbac) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.audit = audit;
        this.rbac = rbac;
    }

    @GetMapping("/agents")
    List<Map<String, Object>> list(HttpServletRequest request) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN", "OPERATOR", "REVIEWER");
        return jdbc.query("""
                select a.id, a.name, a.description, a.status, av.version_number, av.model, av.provider
                from agents a
                left join agent_versions av on av.agent_id = a.id and av.current_version
                where a.workspace_id = :workspace order by a.created_at
                """, Map.of("workspace", membership.workspaceId()), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("status", rs.getString("status"));
            row.put("currentVersion", rs.getObject("version_number") == null ? null : rs.getInt("version_number"));
            row.put("model", rs.getString("model") == null ? "" : rs.getString("model"));
            row.put("provider", rs.getString("provider") == null ? "" : rs.getString("provider"));
            return row;
        });
    }

    @PostMapping("/agents")
    Map<String, Object> create(HttpServletRequest request, @RequestBody CreateAgent body) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into agents (id, workspace_id, name, description, status, created_by)
                values (:id, :workspace, :name, :description, 'INACTIVE', :user)
                """, Map.of(
                "id", id,
                "workspace", membership.workspaceId(),
                "name", body.name(),
                "description", body.description() == null ? "" : body.description(),
                "user", rbac.current().id()));
        audit.record(membership.workspaceId(), rbac.current().id(), "AGENT_CREATED", "agent", id.toString(), null, Map.of("name", body.name()));
        return Map.of("id", id, "status", "INACTIVE");
    }

    @PostMapping("/agents/{id}/versions")
    Map<String, Object> version(HttpServletRequest request, @PathVariable UUID id, @RequestBody VersionRequest body) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        if (body.toolNames() == null || body.toolNames().isEmpty() || body.systemPrompt() == null || body.systemPrompt().isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "A system prompt and at least one tool are required.", 400);
        }
        if (body.maxToolCalls() < 1 || body.maxTokens() < 1 || body.timeoutMs() < 1000 || body.tokenBudget() < 1) {
            throw new ApiException("VALIDATION_FAILED", "Budgets and timeouts must be positive.", 400);
        }
        return tx.execute(status -> {
            Integer agentCount = jdbc.queryForObject(
                    "select count(*)::int from agents where id = :id and workspace_id = :workspace",
                    Map.of("id", id, "workspace", membership.workspaceId()), Integer.class);
            if (agentCount == null || agentCount == 0) {
                throw new ApiException("NOT_FOUND", "Agent not found.", 404);
            }
            int promptNumber = nextNumber("prompt_versions", id);
            int versionNumber = nextNumber("agent_versions", id);
            UUID promptId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            jdbc.update("""
                    insert into prompt_versions (id, agent_id, version_number, system_prompt, created_by)
                    values (:id, :agent, :version, :prompt, :user)
                    """, Map.of("id", promptId, "agent", id, "version", promptNumber, "prompt", body.systemPrompt(), "user", rbac.current().id()));
            var toolIds = jdbc.query("select id, name from tools where name in (:names)",
                    new MapSqlParameterSource("names", body.toolNames()),
                    (rs, n) -> Map.entry(rs.getString("name"), UUID.fromString(rs.getString("id"))));
            if (toolIds.size() != body.toolNames().size()) {
                throw new ApiException("VALIDATION_FAILED", "One or more tools are not registered.", 400);
            }
            var snapshot = new LinkedHashMap<String, Object>();
            snapshot.put("version", versionNumber);
            snapshot.put("provider", body.provider());
            snapshot.put("model", body.model());
            snapshot.put("temperature", body.temperature());
            snapshot.put("maxTokens", body.maxTokens());
            snapshot.put("timeoutMs", body.timeoutMs());
            snapshot.put("maxToolCalls", body.maxToolCalls());
            snapshot.put("costBudgetUsd", body.costBudgetUsd());
            snapshot.put("tokenBudget", body.tokenBudget());
            snapshot.put("systemPrompt", body.systemPrompt());
            snapshot.put("knowledgeBaseId", body.knowledgeBaseId());
            snapshot.put("embeddingModel", embeddingModel(body.knowledgeBaseId(), membership.workspaceId()));
            snapshot.put("tools", body.toolNames());
            snapshot.put("environment", body.environment());
            jdbc.update("update agent_versions set current_version = false where agent_id = :agent", Map.of("agent", id));
            jdbc.update("""
                    insert into agent_versions (
                        id, agent_id, version_number, provider, model, temperature, max_tokens, timeout_ms, max_tool_calls,
                        cost_budget_usd, prompt_version_id, knowledge_base_id, environment, current_version, snapshot, created_by
                    ) values (
                        :id, :agent, :version, :provider, :model, :temperature, :maxTokens, :timeoutMs, :maxToolCalls,
                        :budget, :prompt, :kb, :environment, true, :snapshot, :user
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", versionId)
                    .addValue("agent", id)
                    .addValue("version", versionNumber)
                    .addValue("provider", body.provider())
                    .addValue("model", body.model())
                    .addValue("temperature", body.temperature())
                    .addValue("maxTokens", body.maxTokens())
                    .addValue("timeoutMs", body.timeoutMs())
                    .addValue("maxToolCalls", body.maxToolCalls())
                    .addValue("budget", body.costBudgetUsd())
                    .addValue("prompt", promptId)
                    .addValue("kb", UUID.fromString(body.knowledgeBaseId()))
                    .addValue("environment", body.environment())
                    .addValue("snapshot", Jsons.jsonb(Jsons.write(mapper, snapshot)))
                    .addValue("user", rbac.current().id()));
            for (var tool : toolIds) {
                jdbc.update("insert into agent_version_tools (agent_version_id, tool_id) values (:version, :tool)",
                        Map.of("version", versionId, "tool", tool.getValue()));
            }
            audit.record(membership.workspaceId(), rbac.current().id(), "AGENT_VERSION_CREATED", "agent_version",
                    versionId.toString(), null, Map.of("version", versionNumber));
            return Map.of("id", versionId, "version", versionNumber, "promptVersionId", promptId);
        });
    }

    @PostMapping("/agents/{id}/status")
    Map<String, Object> status(HttpServletRequest request, @PathVariable UUID id, @RequestBody StatusRequest body) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        if (!"ACTIVE".equals(body.status()) && !"INACTIVE".equals(body.status())) {
            throw new ApiException("VALIDATION_FAILED", "Status must be ACTIVE or INACTIVE.", 400);
        }
        int updated = jdbc.update("update agents set status = :status where id = :id and workspace_id = :workspace",
                Map.of("status", body.status(), "id", id, "workspace", membership.workspaceId()));
        if (updated == 0) {
            throw new ApiException("NOT_FOUND", "Agent not found.", 404);
        }
        audit.record(membership.workspaceId(), rbac.current().id(), "AGENT_STATUS_CHANGED", "agent", id.toString(), null,
                Map.of("status", body.status()));
        return Map.of("id", id, "status", body.status());
    }

    @GetMapping("/agents/{id}/versions")
    List<Map<String, Object>> versions(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        return jdbc.query("""
                select av.id, av.version_number, av.provider, av.model, av.current_version, av.created_at, av.snapshot::text as snapshot
                from agent_versions av join agents a on a.id = av.agent_id
                where av.agent_id = :id and a.workspace_id = :workspace
                order by av.version_number desc
                """, Map.of("id", id, "workspace", membership.workspaceId()), (rs, n) -> Map.of(
                "id", UUID.fromString(rs.getString("id")),
                "version", rs.getInt("version_number"),
                "provider", rs.getString("provider"),
                "model", rs.getString("model"),
                "current", rs.getBoolean("current_version"),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString(),
                "snapshot", Jsons.map(mapper, rs.getString("snapshot"))
        ));
    }

    @GetMapping("/tools")
    List<Map<String, Object>> tools(HttpServletRequest request) {
        rbac.require(request, "DEVELOPER", "ADMIN");
        return jdbc.query("""
                select name, description, classification, required_permission, risk_level, approval_required, timeout_ms, idempotency_required
                from tools order by name
                """, Map.of(), (rs, n) -> Map.of(
                "name", rs.getString("name"),
                "description", rs.getString("description"),
                "classification", rs.getString("classification"),
                "requiredPermission", rs.getString("required_permission"),
                "riskLevel", rs.getString("risk_level"),
                "approvalRequired", rs.getBoolean("approval_required"),
                "timeoutMs", rs.getInt("timeout_ms"),
                "idempotencyRequired", rs.getBoolean("idempotency_required")
        ));
    }

    private int nextNumber(String table, UUID agentId) {
        Integer value = jdbc.queryForObject("select coalesce(max(version_number), 0) + 1 from " + table + " where agent_id = :agent",
                Map.of("agent", agentId), Integer.class);
        return value == null ? 1 : value;
    }

    private String embeddingModel(String knowledgeBaseId, UUID workspaceId) {
        var rows = jdbc.query("""
                select embedding_model from knowledge_bases where id = :id and workspace_id = :workspace
                """, Map.of("id", UUID.fromString(knowledgeBaseId), "workspace", workspaceId), (rs, n) -> rs.getString(1));
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Knowledge base not found.", 404);
        }
        return rows.get(0);
    }

    public record CreateAgent(@NotBlank String name, String description) {}
    public record StatusRequest(String status) {}
    public record VersionRequest(
            String provider, String model, double temperature, int maxTokens, int timeoutMs, int maxToolCalls,
            BigDecimal costBudgetUsd, int tokenBudget, String systemPrompt, String knowledgeBaseId,
            List<String> toolNames, String environment
    ) {}
}
