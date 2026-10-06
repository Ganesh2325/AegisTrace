package com.aegistrace.agent;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.knowledge.KnowledgeService;
import com.aegistrace.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final KnowledgeService knowledge;

    public AgentService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, AuditService audit,
                        KnowledgeService knowledge) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.audit = audit;
        this.knowledge = knowledge;
    }

    public List<Map<String, Object>> list(UUID workspaceId) {
        return jdbc.query("""
                select a.id, a.name, a.description, a.status, a.created_at,
                       av.id as version_id, av.version_number, av.model, av.provider,
                       kb.name as knowledge_name,
                       (select count(*) from agent_version_tools avt where avt.agent_version_id = av.id) as tool_count,
                       (select count(*) from agent_runs r where r.agent_id = a.id) as run_count,
                       (select max(r.created_at) from agent_runs r where r.agent_id = a.id) as last_run_at,
                       (select max(v.created_at) from agent_versions v where v.agent_id = a.id) as last_version_at
                from agents a
                left join agent_versions av on av.agent_id = a.id and av.current_version
                left join knowledge_bases kb on kb.id = av.knowledge_base_id
                where a.workspace_id = :workspace
                order by a.created_at
                """, Map.of("workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("status", rs.getString("status"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("updatedAt", rs.getTimestamp("last_version_at") == null
                    ? rs.getTimestamp("created_at").toInstant().toString()
                    : rs.getTimestamp("last_version_at").toInstant().toString());
            row.put("currentVersion", rs.getObject("version_number") == null ? null : rs.getInt("version_number"));
            row.put("currentVersionId", rs.getString("version_id") == null ? null : UUID.fromString(rs.getString("version_id")));
            row.put("model", rs.getString("model"));
            row.put("provider", rs.getString("provider"));
            row.put("knowledgeName", rs.getString("knowledge_name"));
            row.put("toolCount", rs.getObject("version_id") == null ? null : rs.getInt("tool_count"));
            row.put("runCount", rs.getLong("run_count"));
            row.put("lastRunAt", rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant().toString());
            return row;
        });
    }

    public Map<String, Object> get(Actor actor, UUID workspaceId, UUID agentId) {
        var rows = listDetail(workspaceId, agentId);
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Agent not found.", 404);
        }
        var membership = actor.membership(workspaceId);
        if (membership == null) {
            throw new ApiException("FORBIDDEN", "You are not a member of that workspace.", 403);
        }
        var body = new LinkedHashMap<String, Object>(rows.get(0));
        String role = membership.role();
        body.put("activeVersion", currentVersion(workspaceId, agentId));
        body.put("usage", usage(agentId, workspaceId));
        body.put("recentRuns", recentRuns(actor, workspaceId, agentId, role));
        body.put("capabilities", Map.of(
                "canConfigure", AgentAccess.canConfigure(role),
                "canChangeStatus", AgentAccess.canConfigure(role)
        ));
        return body;
    }

    public Map<String, Object> create(Actor actor, UUID workspaceId, String name, String description) {
        if (name == null || name.isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "A name is required.", 400);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into agents (id, workspace_id, name, description, status, created_by)
                values (:id, :workspace, :name, :description, 'INACTIVE', :user)
                """, Map.of(
                "id", id,
                "workspace", workspaceId,
                "name", name.trim(),
                "description", description == null ? "" : description,
                "user", actor.id()));
        audit.record(workspaceId, actor.id(), "AGENT_CREATED", "agent", id.toString(), null, Map.of("name", name.trim()));
        return Map.of("id", id, "status", "INACTIVE");
    }

    public Map<String, Object> createVersion(Actor actor, UUID workspaceId, UUID agentId, CreateVersion body) {
        if (body.toolNames() == null || body.toolNames().isEmpty()) {
            throw new ApiException("VALIDATION_FAILED", "At least one registered tool is required.", 400);
        }
        boolean reusePrompt = body.promptVersionId() != null && !body.promptVersionId().isBlank();
        if (!reusePrompt && (body.systemPrompt() == null || body.systemPrompt().isBlank())) {
            throw new ApiException("VALIDATION_FAILED", "A system prompt or an existing prompt version is required.", 400);
        }
        if (body.maxToolCalls() < 1 || body.maxTokens() < 1 || body.timeoutMs() < 1000 || body.tokenBudget() < 1) {
            throw new ApiException("VALIDATION_FAILED", "Budgets and timeouts must be positive.", 400);
        }
        if (body.provider() == null || body.provider().isBlank() || body.model() == null || body.model().isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "Provider and model are required.", 400);
        }
        if (body.environment() == null || body.environment().isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "Environment is required.", 400);
        }
        if (body.knowledgeBaseId() == null || body.knowledgeBaseId().isBlank()) {
            throw new ApiException("VALIDATION_FAILED", "A knowledge base is required.", 400);
        }
        return tx.execute(status -> {
            Integer agentCount = jdbc.queryForObject(
                    "select count(*)::int from agents where id = :id and workspace_id = :workspace",
                    Map.of("id", agentId, "workspace", workspaceId), Integer.class);
            if (agentCount == null || agentCount == 0) {
                throw new ApiException("NOT_FOUND", "Agent not found.", 404);
            }
            UUID promptId;
            int promptNumber;
            String promptText;
            if (reusePrompt) {
                UUID existing = UUID.fromString(body.promptVersionId());
                var prompts = jdbc.query("""
                        select id, version_number, system_prompt from prompt_versions
                        where id = :id and agent_id = :agent
                        """, Map.of("id", existing, "agent", agentId), (rs, n) -> Map.of(
                        "id", UUID.fromString(rs.getString("id")),
                        "version", rs.getInt("version_number"),
                        "text", rs.getString("system_prompt")
                ));
                if (prompts.isEmpty()) {
                    throw new ApiException("NOT_FOUND", "Prompt version not found.", 404);
                }
                promptId = (UUID) prompts.get(0).get("id");
                promptNumber = ((Number) prompts.get(0).get("version")).intValue();
                promptText = String.valueOf(prompts.get(0).get("text"));
            } else {
                promptNumber = nextNumber("prompt_versions", agentId);
                promptId = UUID.randomUUID();
                promptText = body.systemPrompt();
                jdbc.update("""
                        insert into prompt_versions (id, agent_id, version_number, system_prompt, created_by)
                        values (:id, :agent, :version, :prompt, :user)
                        """, Map.of("id", promptId, "agent", agentId, "version", promptNumber, "prompt", promptText, "user", actor.id()));
                audit.record(workspaceId, actor.id(), "PROMPT_VERSION_CREATED", "prompt_version", promptId.toString(), null,
                        Map.of("version", promptNumber));
            }
            var toolIds = jdbc.query("select id, name from tools where name in (:names)",
                    new MapSqlParameterSource("names", body.toolNames()),
                    (rs, n) -> Map.entry(rs.getString("name"), UUID.fromString(rs.getString("id"))));
            if (toolIds.size() != body.toolNames().size()) {
                throw new ApiException("VALIDATION_FAILED", "One or more tools are not registered.", 400);
            }
            int versionNumber = nextNumber("agent_versions", agentId);
            UUID versionId = UUID.randomUUID();
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
            snapshot.put("systemPrompt", promptText);
            var knowledgeRef = knowledge.resolveKnowledgeVersion(workspaceId, body.knowledgeBaseId(), body.knowledgeBaseVersionId());
            snapshot.put("knowledgeBaseId", body.knowledgeBaseId());
            snapshot.put("knowledgeBaseVersionId", knowledgeRef.get("knowledgeBaseVersionId").toString());
            snapshot.put("knowledgeVersion", knowledgeRef.get("knowledgeVersion"));
            snapshot.put("embeddingModel", knowledgeRef.get("embeddingModel"));
            snapshot.put("tools", body.toolNames());
            snapshot.put("environment", body.environment());
            snapshot.put("promptVersionId", promptId.toString());
            jdbc.update("""
                    insert into agent_versions (
                        id, agent_id, version_number, provider, model, temperature, max_tokens, timeout_ms, max_tool_calls,
                        cost_budget_usd, prompt_version_id, knowledge_base_id, knowledge_base_version_id, environment,
                        current_version, snapshot, created_by
                    ) values (
                        :id, :agent, :version, :provider, :model, :temperature, :maxTokens, :timeoutMs, :maxToolCalls,
                        :budget, :prompt, :kb, :kbVersion, :environment, false, :snapshot, :user
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", versionId)
                    .addValue("agent", agentId)
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
                    .addValue("kbVersion", knowledgeRef.get("knowledgeBaseVersionId"))
                    .addValue("environment", body.environment())
                    .addValue("snapshot", Jsons.jsonb(Jsons.write(mapper, snapshot)))
                    .addValue("user", actor.id()));
            for (var tool : toolIds) {
                jdbc.update("insert into agent_version_tools (agent_version_id, tool_id) values (:version, :tool)",
                        Map.of("version", versionId, "tool", tool.getValue()));
            }
            audit.record(workspaceId, actor.id(), "AGENT_VERSION_CREATED", "agent_version", versionId.toString(), null,
                    Map.of("version", versionNumber, "current", false));
            return Map.of("id", versionId, "version", versionNumber, "promptVersionId", promptId, "current", false);
        });
    }

    public Map<String, Object> activate(Actor actor, UUID workspaceId, UUID agentId, UUID versionId) {
        return tx.execute(status -> {
            var locked = jdbc.query("""
                    select id from agents where id = :id and workspace_id = :workspace for update
                    """, Map.of("id", agentId, "workspace", workspaceId), (rs, n) -> rs.getString("id"));
            if (locked.isEmpty()) {
                throw new ApiException("NOT_FOUND", "Agent not found.", 404);
            }
            Integer owned = jdbc.queryForObject("""
                    select count(*)::int from agent_versions av
                    join agents a on a.id = av.agent_id
                    where av.id = :version and av.agent_id = :agent and a.workspace_id = :workspace
                    """, Map.of("version", versionId, "agent", agentId, "workspace", workspaceId), Integer.class);
            if (owned == null || owned == 0) {
                throw new ApiException("NOT_FOUND", "Agent version not found.", 404);
            }
            jdbc.update("update agent_versions set current_version = false where agent_id = :agent", Map.of("agent", agentId));
            jdbc.update("update agent_versions set current_version = true where id = :id and agent_id = :agent",
                    Map.of("id", versionId, "agent", agentId));
            Integer versionNumber = jdbc.queryForObject("select version_number from agent_versions where id = :id",
                    Map.of("id", versionId), Integer.class);
            audit.record(workspaceId, actor.id(), "AGENT_VERSION_ACTIVATED", "agent_version", versionId.toString(), null,
                    Map.of("version", versionNumber == null ? 0 : versionNumber));
            return Map.of("id", versionId, "version", versionNumber, "current", true);
        });
    }

    public Map<String, Object> status(Actor actor, UUID workspaceId, UUID agentId, String status) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw new ApiException("VALIDATION_FAILED", "Status must be ACTIVE or INACTIVE.", 400);
        }
        int updated = jdbc.update("update agents set status = :status where id = :id and workspace_id = :workspace",
                Map.of("status", status, "id", agentId, "workspace", workspaceId));
        if (updated == 0) {
            throw new ApiException("NOT_FOUND", "Agent not found.", 404);
        }
        audit.record(workspaceId, actor.id(), "AGENT_STATUS_CHANGED", "agent", agentId.toString(), null, Map.of("status", status));
        return Map.of("id", agentId, "status", status);
    }

    public List<Map<String, Object>> versions(UUID workspaceId, UUID agentId) {
        ensureAgent(workspaceId, agentId);
        return jdbc.query("""
                select av.id, av.version_number, av.provider, av.model, av.temperature, av.max_tokens, av.timeout_ms,
                       av.max_tool_calls, av.cost_budget_usd, av.environment, av.current_version, av.created_at,
                       av.snapshot::text as snapshot, av.prompt_version_id, av.knowledge_base_id,
                       av.knowledge_base_version_id, kv.version_number as knowledge_version,
                       pv.version_number as prompt_number, kb.name as knowledge_name, u.email as created_by_email,
                       (select count(*) from agent_runs r where r.agent_version_id = av.id) as used_by_runs,
                       (select count(*) from agent_version_tools avt where avt.agent_version_id = av.id) as tool_count
                from agent_versions av
                join agents a on a.id = av.agent_id
                join prompt_versions pv on pv.id = av.prompt_version_id
                join knowledge_bases kb on kb.id = av.knowledge_base_id
                left join knowledge_base_versions kv on kv.id = av.knowledge_base_version_id
                join users u on u.id = av.created_by
                where av.agent_id = :id and a.workspace_id = :workspace
                order by av.version_number desc
                """, Map.of("id", agentId, "workspace", workspaceId), (rs, n) -> versionRow(rs));
    }

    public Map<String, Object> version(UUID workspaceId, UUID agentId, UUID versionId) {
        ensureAgent(workspaceId, agentId);
        var rows = jdbc.query("""
                select av.id, av.version_number, av.provider, av.model, av.temperature, av.max_tokens, av.timeout_ms,
                       av.max_tool_calls, av.cost_budget_usd, av.environment, av.current_version, av.created_at,
                       av.snapshot::text as snapshot, av.prompt_version_id, av.knowledge_base_id,
                       av.knowledge_base_version_id, kv.version_number as knowledge_version,
                       pv.version_number as prompt_number, kb.name as knowledge_name, u.email as created_by_email,
                       (select count(*) from agent_runs r where r.agent_version_id = av.id) as used_by_runs,
                       (select count(*) from agent_version_tools avt where avt.agent_version_id = av.id) as tool_count
                from agent_versions av
                join agents a on a.id = av.agent_id
                join prompt_versions pv on pv.id = av.prompt_version_id
                join knowledge_bases kb on kb.id = av.knowledge_base_id
                left join knowledge_base_versions kv on kv.id = av.knowledge_base_version_id
                join users u on u.id = av.created_by
                where av.agent_id = :agent and av.id = :version and a.workspace_id = :workspace
                """, Map.of("agent", agentId, "version", versionId, "workspace", workspaceId), (rs, n) -> versionRow(rs));
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Agent version not found.", 404);
        }
        var body = new LinkedHashMap<String, Object>(rows.get(0));
        body.put("tools", toolsFor(versionId));
        return body;
    }

    public List<Map<String, Object>> tools() {
        return jdbc.query("""
                select name, description, classification, required_permission, risk_level, approval_required, timeout_ms, idempotency_required
                from tools order by name
                """, Map.of(), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("classification", rs.getString("classification"));
            row.put("requiredPermission", rs.getString("required_permission"));
            row.put("risk", rs.getString("risk_level"));
            row.put("approvalRequired", rs.getBoolean("approval_required"));
            row.put("timeoutMs", rs.getInt("timeout_ms"));
            row.put("idempotencyRequired", rs.getBoolean("idempotency_required"));
            return row;
        });
    }

    public List<Map<String, Object>> auditTrail(UUID workspaceId, UUID agentId) {
        ensureAgent(workspaceId, agentId);
        return jdbc.query("""
                select id, action, resource_type, resource_id, created_at
                from audit_events
                where workspace_id = :workspace
                  and (
                    resource_id = :agent
                    or resource_id in (select id::text from agent_versions where agent_id = :agentUuid)
                    or resource_id in (select id::text from prompt_versions where agent_id = :agentUuid)
                  )
                order by created_at desc
                limit 20
                """, Map.of("workspace", workspaceId, "agent", agentId.toString(), "agentUuid", agentId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("action", rs.getString("action"));
            row.put("resourceType", rs.getString("resource_type"));
            row.put("resourceId", rs.getString("resource_id"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
    }

    private Map<String, Object> versionRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        var snapshot = AgentSnapshots.withoutPrompt(Jsons.map(mapper, rs.getString("snapshot")));
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.fromString(rs.getString("id")));
        row.put("version", rs.getInt("version_number"));
        row.put("current", rs.getBoolean("current_version"));
        row.put("provider", rs.getString("provider"));
        row.put("model", rs.getString("model"));
        row.put("temperature", rs.getDouble("temperature"));
        row.put("maxTokens", rs.getInt("max_tokens"));
        row.put("timeoutMs", rs.getInt("timeout_ms"));
        row.put("maxToolCalls", rs.getInt("max_tool_calls"));
        row.put("costBudgetUsd", rs.getBigDecimal("cost_budget_usd"));
        row.put("tokenBudget", snapshot.get("tokenBudget"));
        row.put("environment", rs.getString("environment"));
        row.put("promptVersionId", UUID.fromString(rs.getString("prompt_version_id")));
        row.put("promptVersionNumber", rs.getInt("prompt_number"));
        row.put("knowledgeBaseId", UUID.fromString(rs.getString("knowledge_base_id")));
        row.put("knowledgeName", rs.getString("knowledge_name"));
        row.put("knowledgeBaseVersionId", rs.getString("knowledge_base_version_id") == null ? null : UUID.fromString(rs.getString("knowledge_base_version_id")));
        row.put("knowledgeVersion", rs.getObject("knowledge_version") == null ? null : rs.getInt("knowledge_version"));
        row.put("knowledgeVersionStatus", rs.getString("knowledge_base_version_id") == null ? "MISSING" : "PINNED");
        row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
        row.put("createdByEmail", rs.getString("created_by_email"));
        row.put("usedByRunCount", rs.getLong("used_by_runs"));
        row.put("toolCount", rs.getInt("tool_count"));
        row.put("snapshot", snapshot);
        return row;
    }

    private List<Map<String, Object>> toolsFor(UUID versionId) {
        return jdbc.query("""
                select t.name, t.classification, t.risk_level, t.approval_required
                from agent_version_tools avt
                join tools t on t.id = avt.tool_id
                where avt.agent_version_id = :id
                order by t.name
                """, Map.of("id", versionId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", rs.getString("name"));
            row.put("classification", rs.getString("classification"));
            row.put("risk", rs.getString("risk_level"));
            row.put("approvalRequired", rs.getBoolean("approval_required"));
            row.put("enabled", true);
            return row;
        });
    }

    private Map<String, Object> currentVersion(UUID workspaceId, UUID agentId) {
        var rows = jdbc.query("""
                select av.id from agent_versions av
                join agents a on a.id = av.agent_id
                where av.agent_id = :id and a.workspace_id = :workspace and av.current_version
                """, Map.of("id", agentId, "workspace", workspaceId), (rs, n) -> UUID.fromString(rs.getString("id")));
        if (rows.isEmpty()) {
            return null;
        }
        return version(workspaceId, agentId, rows.get(0));
    }

    private Map<String, Object> usage(UUID agentId, UUID workspaceId) {
        return jdbc.queryForObject("""
                select count(*) as run_count,
                       count(*) filter (where r.state = 'COMPLETED') as completed,
                       count(*) filter (where r.state in ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT')) as terminal,
                       max(r.created_at) as last_run_at
                from agent_runs r
                where r.agent_id = :id and r.workspace_id = :workspace
                """, Map.of("id", agentId, "workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            long runs = rs.getLong("run_count");
            long terminal = rs.getLong("terminal");
            row.put("runCount", runs);
            row.put("completedCount", rs.getLong("completed"));
            row.put("terminalCount", terminal);
            row.put("lastRunAt", rs.getTimestamp("last_run_at") == null ? null : rs.getTimestamp("last_run_at").toInstant().toString());
            if (terminal == 0) {
                row.put("completionRate", null);
                row.put("completionStatus", "NO_DATA");
            } else {
                row.put("completionRate", rs.getLong("completed") / (double) terminal);
                row.put("completionStatus", "OK");
            }
            return row;
        });
    }

    private List<Map<String, Object>> recentRuns(com.aegistrace.security.Actor actor, UUID workspaceId, UUID agentId, String role) {
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("agent", agentId)
                .addValue("user", actor.id());
        return jdbc.query("""
                select r.id, r.state, r.created_at, av.version_number
                from agent_runs r
                join agent_versions av on av.id = r.agent_version_id
                where r.agent_id = :agent and r.workspace_id = :workspace
                """ + com.aegistrace.run.RunService.visibilitySql(role) + """
                order by r.created_at desc
                limit 10
                """, params, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("state", rs.getString("state"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("agentVersionNumber", rs.getInt("version_number"));
            return row;
        });
    }

    private List<Map<String, Object>> listDetail(UUID workspaceId, UUID agentId) {
        return jdbc.query("""
                select a.id, a.name, a.description, a.status, a.created_at, w.name as workspace_name, u.email as created_by_email,
                       (select max(v.created_at) from agent_versions v where v.agent_id = a.id) as updated_at
                from agents a
                join workspaces w on w.id = a.workspace_id
                join users u on u.id = a.created_by
                where a.id = :id and a.workspace_id = :workspace
                """, Map.of("id", agentId, "workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("status", rs.getString("status"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("updatedAt", rs.getTimestamp("updated_at") == null
                    ? rs.getTimestamp("created_at").toInstant().toString()
                    : rs.getTimestamp("updated_at").toInstant().toString());
            row.put("workspaceName", rs.getString("workspace_name"));
            row.put("createdByEmail", rs.getString("created_by_email"));
            return row;
        });
    }

    private void ensureAgent(UUID workspaceId, UUID agentId) {
        Integer count = jdbc.queryForObject(
                "select count(*)::int from agents where id = :id and workspace_id = :workspace",
                Map.of("id", agentId, "workspace", workspaceId), Integer.class);
        if (count == null || count == 0) {
            throw new ApiException("NOT_FOUND", "Agent not found.", 404);
        }
    }

    private int nextNumber(String table, UUID agentId) {
        Integer value = jdbc.queryForObject("select coalesce(max(version_number), 0) + 1 from " + table + " where agent_id = :agent",
                Map.of("agent", agentId), Integer.class);
        return value == null ? 1 : value;
    }

    public record CreateVersion(
            String provider, String model, double temperature, int maxTokens, int timeoutMs, int maxToolCalls,
            BigDecimal costBudgetUsd, int tokenBudget, String systemPrompt, String promptVersionId, String knowledgeBaseId,
            String knowledgeBaseVersionId, List<String> toolNames, String environment
    ) {}
}
