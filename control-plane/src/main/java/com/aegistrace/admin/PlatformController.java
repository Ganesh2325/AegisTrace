package com.aegistrace.admin;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.config.AppProperties;
import com.aegistrace.policy.ArgumentValidator;
import com.aegistrace.policy.PolicyDecision;
import com.aegistrace.policy.PolicyEngine;
import com.aegistrace.security.Rbac;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
class PlatformController {
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final Rbac rbac;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final PolicyEngine policy = new PolicyEngine();

    PlatformController(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwords, AuditService audit, Rbac rbac,
                       AppProperties properties, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.audit = audit;
        this.rbac = rbac;
        this.properties = properties;
        this.mapper = mapper;
    }

    @GetMapping("/audit")
    Map<String, Object> audit(HttpServletRequest request, @RequestParam(defaultValue = "0") int page) {
        var membership = rbac.require(request, "ADMIN", "DEVELOPER");
        int size = 50;
        var items = jdbc.query("""
                select id, actor_id, action, resource_type, resource_id, trace_id, run_id, metadata::text as metadata, created_at
                from audit_events where workspace_id = :workspace
                order by created_at desc limit :limit offset :offset
                """, Map.of("workspace", membership.workspaceId(), "limit", size, "offset", Math.max(page, 0) * size), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("actorId", rs.getString("actor_id") == null ? null : UUID.fromString(rs.getString("actor_id")));
            row.put("action", rs.getString("action"));
            row.put("resourceType", rs.getString("resource_type"));
            row.put("resourceId", rs.getString("resource_id"));
            row.put("traceId", rs.getString("trace_id"));
            row.put("runId", rs.getString("run_id") == null ? null : UUID.fromString(rs.getString("run_id")));
            row.put("metadata", Jsons.map(mapper, rs.getString("metadata")));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
        return Map.of("items", items, "page", Math.max(page, 0), "size", size);
    }

    @GetMapping("/metrics/summary")
    Map<String, Object> metrics(HttpServletRequest request) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return jdbc.queryForObject("""
                select count(*) as runs,
                       count(*) filter (where state = 'COMPLETED') as completed,
                       count(*) filter (where state = 'FAILED') as failed,
                       count(*) filter (where state = 'TIMED_OUT') as timed_out,
                       coalesce(sum(input_tokens + output_tokens), 0) as tokens,
                       coalesce(sum(estimated_cost_usd), 0) as cost,
                       coalesce(percentile_cont(0.5) within group (order by extract(epoch from (ended_at - started_at)) * 1000)
                           filter (where ended_at is not null and started_at is not null), 0) as p50,
                       coalesce(percentile_cont(0.95) within group (order by extract(epoch from (ended_at - started_at)) * 1000)
                           filter (where ended_at is not null and started_at is not null), 0) as p95,
                       coalesce(percentile_cont(0.99) within group (order by extract(epoch from (ended_at - started_at)) * 1000)
                           filter (where ended_at is not null and started_at is not null), 0) as p99
                from agent_runs where workspace_id = :workspace
                """, Map.of("workspace", membership.workspaceId()), (rs, n) -> {
            long runs = rs.getLong("runs");
            long completed = rs.getLong("completed");
            long failed = rs.getLong("failed");
            Long pending = jdbc.queryForObject(
                    "select count(*) from approvals where workspace_id = :workspace and status = 'PENDING'",
                    Map.of("workspace", membership.workspaceId()), Long.class);
            Long denials = jdbc.queryForObject("""
                    select count(*) from tool_proposals p join agent_runs r on r.id = p.run_id
                    where r.workspace_id = :workspace and p.policy_decision = 'DENY'
                    """, Map.of("workspace", membership.workspaceId()), Long.class);
            Long decisions = jdbc.queryForObject("""
                    select count(*) from tool_proposals p join agent_runs r on r.id = p.run_id
                    where r.workspace_id = :workspace and p.policy_decision is not null
                    """, Map.of("workspace", membership.workspaceId()), Long.class);
            Double approvalWait = jdbc.queryForObject("""
                    select coalesce(avg(extract(epoch from (decided_at - requested_at)) * 1000), 0)
                    from approvals where workspace_id = :workspace and decided_at is not null
                    """, Map.of("workspace", membership.workspaceId()), Double.class);
            Long queue = jdbc.queryForObject(
                    "select count(*) from jobs where status in ('PENDING','RETRY','RUNNING')", Map.of(), Long.class);
            Long evals = jdbc.queryForObject(
                    "select count(*) from evaluations where workspace_id = :workspace", Map.of("workspace", membership.workspaceId()), Long.class);
            Long evalPass = jdbc.queryForObject(
                    "select count(*) from evaluations where workspace_id = :workspace and passed", Map.of("workspace", membership.workspaceId()), Long.class);
            var body = new LinkedHashMap<String, Object>();
            body.put("runs", runs);
            body.put("completed", completed);
            body.put("failed", failed);
            body.put("timedOut", rs.getLong("timed_out"));
            body.put("completionRate", runs == 0 ? 0 : (double) completed / runs);
            body.put("failureRate", runs == 0 ? 0 : (double) failed / runs);
            body.put("p50Ms", rs.getDouble("p50"));
            body.put("p95Ms", rs.getDouble("p95"));
            body.put("p99Ms", rs.getDouble("p99"));
            body.put("tokens", rs.getLong("tokens"));
            body.put("estimatedCostUsd", rs.getBigDecimal("cost"));
            body.put("pendingApprovals", pending);
            body.put("policyDenials", denials);
            body.put("policyDecisions", decisions);
            body.put("policyDenialRate", decisions == null || decisions == 0 ? 0 : (double) denials / decisions);
            body.put("approvalWaitMs", approvalWait);
            body.put("queueDepth", queue);
            body.put("evaluations", evals);
            body.put("evaluationPassRate", evals == null || evals == 0 ? 0 : (double) evalPass / evals);
            return body;
        });
    }

    @GetMapping("/evaluations")
    List<Map<String, Object>> evaluations(HttpServletRequest request) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        return jdbc.query("""
                select id, run_id, agent_version_id, prompt_version_id, model, dataset_version, evaluator_version,
                       scores::text as scores, passed, created_at
                from evaluations where workspace_id = :workspace order by created_at desc limit 100
                """, Map.of("workspace", membership.workspaceId()), (rs, n) -> Map.of(
                "id", UUID.fromString(rs.getString("id")),
                "runId", UUID.fromString(rs.getString("run_id")),
                "agentVersionId", UUID.fromString(rs.getString("agent_version_id")),
                "promptVersionId", UUID.fromString(rs.getString("prompt_version_id")),
                "model", rs.getString("model"),
                "datasetVersion", rs.getString("dataset_version"),
                "evaluatorVersion", rs.getString("evaluator_version"),
                "scores", Jsons.map(mapper, rs.getString("scores")),
                "passed", rs.getBoolean("passed"),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString()
        ));
    }

    @GetMapping("/admin/users")
    List<Map<String, Object>> users(HttpServletRequest request) {
        var membership = rbac.require(request, "ADMIN");
        return jdbc.query("""
                select u.id, u.email, u.display_name, u.status, m.role
                from users u join memberships m on m.user_id = u.id
                where m.workspace_id = :workspace order by u.email
                """, Map.of("workspace", membership.workspaceId()), (rs, n) -> Map.of(
                "id", UUID.fromString(rs.getString("id")),
                "email", rs.getString("email"),
                "displayName", rs.getString("display_name"),
                "status", rs.getString("status"),
                "role", rs.getString("role")
        ));
    }

    @PostMapping("/admin/users")
    Map<String, Object> createUser(HttpServletRequest request, @RequestBody UserRequest body) {
        var membership = rbac.require(request, "ADMIN");
        if (body.password() == null || body.password().length() < 12) {
            throw new ApiException("VALIDATION_FAILED", "Passwords must be at least 12 characters.", 400);
        }
        if (!Set.of("DEVELOPER", "OPERATOR", "REVIEWER", "ADMIN").contains(body.role())) {
            throw new ApiException("VALIDATION_FAILED", "Unknown role.", 400);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into users (id, email, password_hash, display_name) values (:id, :email, :hash, :name)
                """, Map.of(
                "id", id,
                "email", body.email().trim().toLowerCase(),
                "hash", passwords.encode(body.password()),
                "name", body.displayName()));
        jdbc.update("insert into memberships (id, workspace_id, user_id, role) values (:id, :workspace, :user, :role)",
                Map.of("id", UUID.randomUUID(), "workspace", membership.workspaceId(), "user", id, "role", body.role()));
        audit.record(membership.workspaceId(), rbac.current().id(), "USER_CREATED", "user", id.toString(), null, Map.of("role", body.role()));
        return Map.of("id", id);
    }

    @PostMapping("/admin/memberships")
    Map<String, Object> membership(HttpServletRequest request, @RequestBody MembershipRequest body) {
        var membership = rbac.require(request, "ADMIN");
        if (!Set.of("DEVELOPER", "OPERATOR", "REVIEWER", "ADMIN").contains(body.role())) {
            throw new ApiException("VALIDATION_FAILED", "Unknown role.", 400);
        }
        jdbc.update("update memberships set role = :role where workspace_id = :workspace and user_id = :user",
                Map.of("role", body.role(), "workspace", membership.workspaceId(), "user", UUID.fromString(body.userId())));
        audit.record(membership.workspaceId(), rbac.current().id(), "ROLE_CHANGED", "membership", body.userId(), null, Map.of("role", body.role()));
        return Map.of("userId", body.userId(), "role", body.role());
    }

    @GetMapping("/admin/settings")
    Map<String, Object> settings(HttpServletRequest request) {
        var membership = rbac.require(request, "ADMIN");
        return settings(membership.workspaceId());
    }

    @PatchMapping("/admin/settings")
    Map<String, Object> updateSettings(HttpServletRequest request, @RequestBody SettingsRequest body) {
        var membership = rbac.require(request, "ADMIN");
        jdbc.update("""
                update workspace_settings set max_runs_per_minute = :rate, approval_ttl_seconds = :ttl,
                    question_retention_days = :days, content_logging = :logging
                where workspace_id = :workspace
                """, new MapSqlParameterSource()
                .addValue("rate", body.maxRunsPerMinute())
                .addValue("ttl", body.approvalTtlSeconds())
                .addValue("days", body.questionRetentionDays())
                .addValue("logging", body.contentLogging())
                .addValue("workspace", membership.workspaceId()));
        audit.record(membership.workspaceId(), rbac.current().id(), "SETTINGS_CHANGED", "workspace_settings",
                membership.workspaceId().toString(), null, Map.of("maxRunsPerMinute", body.maxRunsPerMinute()));
        return settings(membership.workspaceId());
    }

    @PostMapping("/admin/policy-check")
    Map<String, Object> policyCheck(HttpServletRequest request, @RequestBody PolicyCheck body) {
        var membership = rbac.require(request, "DEVELOPER", "ADMIN");
        var validation = ArgumentValidator.validate(body.tool(), body.arguments());
        var tools = jdbc.query("select classification, approval_required, required_permission from tools where name = :name",
                Map.of("name", body.tool()), (rs, n) -> new String[]{rs.getString(1), String.valueOf(rs.getBoolean(2)), rs.getString(3)});
        boolean registered = !tools.isEmpty();
        PolicyDecision decision = policy.evaluate(new PolicyEngine.PolicyRequest(
                body.tool(), body.arguments(), registered, body.assigned(), validation.valid() || !registered, validation.error(),
                body.userHasPermission(), body.priorToolCalls(), body.maxToolCalls(), body.budgetExceeded(),
                registered ? tools.get(0)[0] : "WRITE", registered && Boolean.parseBoolean(tools.get(0)[1]),
                body.arguments() == null ? null : (String) body.arguments().get("priority")));
        audit.record(membership.workspaceId(), rbac.current().id(), "POLICY_DRY_RUN", "tool", body.tool(), null,
                Map.of("decision", decision.kind().name(), "code", decision.code()));
        return Map.of("decision", decision.kind().name(), "code", decision.code(), "reason", decision.reason(),
                "requiredRole", decision.requiredRole() == null ? "" : decision.requiredRole());
    }

    @PostMapping("/admin/failure-simulations")
    Map<String, Object> simulate(HttpServletRequest request, @RequestBody Simulation body) {
        rbac.require(request, "ADMIN");
        if (!properties.isFailureSimulationEnabled() || properties.isProduction()) {
            throw new ApiException("NOT_FOUND", "Failure simulation is disabled.", 404);
        }
        if (!Set.of("ticket_500", "ticket_400", "ticket_timeout", "crash_before_insert", "crash_after_insert").contains(body.mode())) {
            throw new ApiException("VALIDATION_FAILED", "Unknown simulation mode.", 400);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into jobs (id, job_type, payload, status, max_attempts, idempotency_key)
                values (:id, 'SIMULATE', :payload, 'PENDING', 3, :key)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("payload", Jsons.jsonb(Jsons.write(mapper, Map.of("mode", body.mode()))))
                .addValue("key", "sim:" + id));
        return Map.of("jobId", id, "mode", body.mode());
    }

    @GetMapping("/admin/jobs")
    List<Map<String, Object>> jobs(HttpServletRequest request) {
        rbac.require(request, "ADMIN");
        return jdbc.query("""
                select id, job_type, status, attempts, max_attempts, last_error, next_run_at, created_at
                from jobs order by created_at desc limit 100
                """, Map.of(), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("type", rs.getString("job_type"));
            row.put("status", rs.getString("status"));
            row.put("attempts", rs.getInt("attempts"));
            row.put("maxAttempts", rs.getInt("max_attempts"));
            row.put("lastError", rs.getString("last_error"));
            row.put("nextRunAt", rs.getTimestamp("next_run_at").toInstant().toString());
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
    }

    private Map<String, Object> settings(UUID workspaceId) {
        return jdbc.queryForObject("""
                select max_runs_per_minute, approval_ttl_seconds, question_retention_days, content_logging
                from workspace_settings where workspace_id = :id
                """, Map.of("id", workspaceId), (rs, n) -> Map.of(
                "maxRunsPerMinute", rs.getInt(1),
                "approvalTtlSeconds", rs.getInt(2),
                "questionRetentionDays", rs.getInt(3),
                "contentLogging", rs.getBoolean(4)
        ));
    }

    public record UserRequest(String email, String displayName, String password, String role) {}
    public record MembershipRequest(String userId, String role) {}
    public record SettingsRequest(int maxRunsPerMinute, int approvalTtlSeconds, int questionRetentionDays, boolean contentLogging) {}
    public record PolicyCheck(String tool, Map<String, Object> arguments, boolean assigned, boolean userHasPermission,
                              int priorToolCalls, int maxToolCalls, boolean budgetExceeded) {}
    public record Simulation(String mode) {}
}
