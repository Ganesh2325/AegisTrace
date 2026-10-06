package com.aegistrace.run;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.policy.ArgumentValidator;
import com.aegistrace.policy.PolicyDecision;
import com.aegistrace.policy.PolicyEngine;
import com.aegistrace.runtime.RuntimeClient;
import com.aegistrace.security.Actor;
import com.aegistrace.security.Rbac;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final RunEventBus events;
    private final RuntimeClient runtime;
    private final PolicyEngine policy = new PolicyEngine();
    private final MeterRegistry meters;
    private final Tracer tracer;
    private final ApprovalService approvals;
    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "aegis-run");
        thread.setDaemon(true);
        return thread;
    });

    public RunService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper, AuditService audit,
                      RunEventBus events, RuntimeClient runtime, MeterRegistry meters, ObjectProvider<Tracer> tracer,
                      ApprovalService approvals) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
        this.runtime = runtime;
        this.meters = meters;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.approvals = approvals;
    }

    public Map<String, Object> create(Actor actor, UUID workspaceId, String question, UUID agentId) {
        if (question == null || question.isBlank() || question.length() > 4000) {
            throw new ApiException("VALIDATION_FAILED", "A question up to 4000 characters is required.", 400);
        }
        var membership = actor.membership(workspaceId);
        if (membership == null) {
            throw new ApiException("FORBIDDEN", "You are not a member of that workspace.", 403);
        }
        UUID runId = UUID.randomUUID();
        var span = tracer.nextSpan().name("aegistrace.agent.run");
        span.tag("run.id", runId.toString());
        span.tag("workspace.id", workspaceId.toString());
        span.start();
        String traceId = span.context().traceId();
        if (traceId == null || traceId.isBlank() || traceId.chars().allMatch(ch -> ch == '0')) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String requestId = MDC.get("request_id") == null ? UUID.randomUUID().toString() : MDC.get("request_id");
        String finalTrace = traceId;
        String finalSpan = com.aegistrace.observability.W3cTraceContext.spanId(span.context().spanId());
        tx.executeWithoutResult(status -> {
            jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key, 0))",
                    Map.of("key", "run:" + actor.id()), rs -> null);
            Integer limit = jdbc.queryForObject(
                    "select max_runs_per_minute from workspace_settings where workspace_id = :id",
                    Map.of("id", workspaceId), Integer.class);
            Long recent = jdbc.queryForObject(
                    "select count(*) from agent_runs where user_id = :user and created_at > now() - interval '1 minute'",
                    Map.of("user", actor.id()), Long.class);
            if (recent != null && limit != null && recent >= limit) {
                throw new ApiException("RATE_LIMITED", "Run rate limit exceeded.", 429);
            }
            AgentVersion version = loadVersion(workspaceId, agentId);
            var snapshot = version.snapshot();
            Object knowledgeVersionId = snapshot.get("knowledgeBaseVersionId");
            var params = new MapSqlParameterSource()
                    .addValue("id", runId)
                    .addValue("workspaceId", workspaceId)
                    .addValue("userId", actor.id())
                    .addValue("agentId", version.agentId())
                    .addValue("agentVersionId", version.id())
                    .addValue("promptVersionId", version.promptVersionId())
                    .addValue("knowledgeBaseId", version.knowledgeBaseId())
                    .addValue("knowledgeBaseVersionId", knowledgeVersionId == null || knowledgeVersionId.toString().isBlank()
                            ? null : UUID.fromString(knowledgeVersionId.toString()))
                    .addValue("requestId", requestId)
                    .addValue("traceId", finalTrace)
                    .addValue("spanId", finalSpan)
                    .addValue("question", question.trim())
                    .addValue("provider", version.provider())
                    .addValue("model", version.model())
                    .addValue("snapshot", Jsons.jsonb(Jsons.write(mapper, snapshot)))
                    .addValue("timeoutAt", java.sql.Timestamp.from(Instant.now().plusMillis(((Number) snapshot.get("timeoutMs")).longValue())));
            jdbc.update("""
                    insert into agent_runs (
                        id, workspace_id, user_id, agent_id, agent_version_id, prompt_version_id, knowledge_base_id,
                        knowledge_base_version_id, request_id, trace_id, span_id, state, question, provider, model, snapshot, timeout_at
                    ) values (
                        :id, :workspaceId, :userId, :agentId, :agentVersionId, :promptVersionId, :knowledgeBaseId,
                        :knowledgeBaseVersionId, :requestId, :traceId, :spanId, 'QUEUED', :question, :provider, :model, :snapshot, :timeoutAt
                    )
                    """, params);
            audit.record(workspaceId, actor.id(), "RUN_CREATED", "agent_run", runId.toString(), runId, Map.of(
                    "agentVersionId", version.id().toString()));
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    var context = MDC.getCopyOfContextMap();
                    executor.execute(() -> {
                        if (context != null) {
                            MDC.setContextMap(context);
                        }
                        MDC.put("run_id", runId.toString());
                        MDC.put("trace_id", finalTrace);
                        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                            orchestrate(runId);
                        } catch (Exception ex) {
                            log.error("orchestration_failed error_type={}", ex.getClass().getSimpleName());
                            fail(runId, "INTERNAL", "INTERNAL", "The run failed.");
                        } finally {
                            span.end();
                            MDC.clear();
                        }
                    });
                }
            });
        });
        meters.counter("aegis.runs", "result", "started").increment();
        return execution(actor, workspaceId, runId);
    }

    public void orchestrate(UUID runId) {
        if (!transition(runId, "RUNNING")) {
            return;
        }
        jdbc.update("update agent_runs set started_at = coalesce(started_at, now()) where id = :id", Map.of("id", runId));
        events.append(runId, "RUN_STARTED", "RUNNING", Map.of());
        RunRow run = lockless(runId);
        Map<String, Object> snapshot = Jsons.map(mapper, run.snapshot());
        int tokenBudget = ((Number) snapshot.getOrDefault("tokenBudget", 8000)).intValue();
        int estimated = run.question().length() / 4 + 500;
        if (estimated > tokenBudget) {
            fail(runId, "BUDGET_EXCEEDED", "BUDGET_EXCEEDED", "Estimated tokens exceed the run budget.");
            return;
        }
        var span = tracer.nextSpan().name("aegistrace.configuration.load").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("agent.version_id", String.valueOf(snapshot.get("agentVersionId") == null ? snapshot.get("version") : snapshot.get("agentVersionId")));
            span.tag("run.id", runId.toString());
        } finally {
            span.end();
        }
        var searchArgs = new LinkedHashMap<String, Object>();
        searchArgs.put("query", run.question());
        searchArgs.put("topK", 4);
        PolicyDecision search = evaluateTool(run, snapshot, "search_knowledge", searchArgs, 0, false);
        events.append(runId, "POLICY_DECIDED", runState(runId), Map.of(
                "tool", "search_knowledge", "decision", search.kind().name(), "code", search.code()));
        audit.record(run.workspaceId(), run.userId(), "POLICY_DECISION", "tool", "search_knowledge", runId, Map.of(
                "decision", search.kind().name(), "code", search.code()));
        if (search.denied()) {
            complete(runId, "Knowledge retrieval was denied by policy. No ticket was created.", "POLICY_DENIED");
            return;
        }
        if (!transition(runId, "RETRIEVING")) {
            return;
        }
        events.append(runId, "RETRIEVAL_STARTED", "RETRIEVING", Map.of("tool", "search_knowledge"));
        RuntimeClient.Plan plan = callRuntime(run, snapshot);
        if (plan == null) {
            return;
        }
        events.append(runId, "RETRIEVAL_COMPLETED", "RETRIEVING", Map.of("chunkCount", plan.retrievedChunkCount()));
        if (!transition(runId, "THINKING")) {
            return;
        }
        events.append(runId, "MODEL_STARTED", "THINKING", Map.of("provider", plan.provider(), "model", plan.model()));
        BigDecimal cost = estimateCost(plan.model().isBlank() ? String.valueOf(snapshot.get("model")) : plan.model(),
                plan.inputTokens(), plan.outputTokens());
        boolean budgetExceeded = plan.inputTokens() + plan.outputTokens() > tokenBudget
                || cost.compareTo(new BigDecimal(snapshot.get("costBudgetUsd").toString())) > 0;
        jdbc.update("""
                update agent_runs set input_tokens = :inTok, output_tokens = :outTok, estimated_cost_usd = :cost,
                    draft_answer = :draft, citations = :citations
                where id = :id
                """, new MapSqlParameterSource()
                .addValue("inTok", plan.inputTokens())
                .addValue("outTok", plan.outputTokens())
                .addValue("cost", cost)
                .addValue("draft", plan.answer())
                .addValue("citations", Jsons.jsonb(Jsons.write(mapper, plan.citations())))
                .addValue("id", runId));
        meters.counter("aegis.tokens", "kind", "input").increment(plan.inputTokens());
        meters.counter("aegis.tokens", "kind", "output").increment(plan.outputTokens());
        meters.counter("aegis.estimated.cost.usd").increment(cost.doubleValue());
        meters.timer("aegis.retrieval.latency").record(java.time.Duration.ofMillis(plan.retrievalLatencyMs()));
        meters.timer("aegis.model.latency").record(java.time.Duration.ofMillis(plan.modelLatencyMs()));
        events.append(runId, "MODEL_COMPLETED", "THINKING", Map.of(
                "inputTokens", plan.inputTokens(),
                "outputTokens", plan.outputTokens(),
                "abstained", plan.abstained(),
                "supported", plan.supported()));
        if (plan.toolProposal() == null) {
            complete(runId, plan.answer(), null);
            return;
        }
        if (!transition(runId, "TOOL_PROPOSED")) {
            return;
        }
        String toolName = String.valueOf(plan.toolProposal().get("tool"));
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = plan.toolProposal().get("arguments") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : new LinkedHashMap<>();
        String reason = String.valueOf(plan.toolProposal().getOrDefault("reason", ""));
        String risk = String.valueOf(plan.toolProposal().getOrDefault("risk", "MEDIUM"));
        UUID proposalId = UUID.randomUUID();
        jdbc.update("""
                insert into tool_proposals (
                    id, run_id, sequence, tool_name, arguments, reason, risk_level, idempotency_key
                ) values (
                    :id, :runId, 2, :tool, :arguments, :reason, :risk, :key
                )
                """, new MapSqlParameterSource()
                .addValue("id", proposalId)
                .addValue("runId", runId)
                .addValue("tool", toolName)
                .addValue("arguments", Jsons.jsonb(Jsons.write(mapper, arguments)))
                .addValue("reason", reason)
                .addValue("risk", risk)
                .addValue("key", "proposal:" + runId + ":2"));
        events.append(runId, "TOOL_PROPOSED", "TOOL_PROPOSED", Map.of(
                "proposalId", proposalId, "tool", toolName, "risk", risk));
        PolicyDecision decision = evaluateTool(run, snapshot, toolName, arguments, 1, budgetExceeded);
        jdbc.update("""
                update tool_proposals set policy_decision = :decision, policy_code = :code, policy_reason = :reason,
                    required_role = :role where id = :id
                """, new MapSqlParameterSource()
                .addValue("decision", decision.kind().name())
                .addValue("code", decision.code())
                .addValue("reason", decision.reason())
                .addValue("role", decision.requiredRole())
                .addValue("id", proposalId));
        meters.counter("aegis.policy.decisions", "decision", decision.kind().name()).increment();
        events.append(runId, "POLICY_DECIDED", "TOOL_PROPOSED", Map.of(
                "proposalId", proposalId, "decision", decision.kind().name(), "code", decision.code()));
        audit.record(run.workspaceId(), run.userId(), "POLICY_DECISION", "tool_proposal", proposalId.toString(), runId, Map.of(
                "decision", decision.kind().name(), "code", decision.code(), "tool", toolName));
        if (decision.denied() || decision.kind() == PolicyDecision.Kind.ALLOW) {
            String suffix = decision.denied()
                    ? "\n\nThe proposed action was denied by policy (" + decision.code() + "). No ticket was created."
                    : "\n\nThe proposed action was not a write that this control plane executes inline. No ticket was created.";
            complete(runId, plan.answer() + suffix, decision.denied() ? "POLICY_DENIED" : null);
            return;
        }
        Integer ttl = jdbc.queryForObject(
                "select approval_ttl_seconds from workspace_settings where workspace_id = :id",
                Map.of("id", run.workspaceId()), Integer.class);
        UUID approvalId = UUID.randomUUID();
        jdbc.update("""
                insert into approvals (
                    id, workspace_id, run_id, proposal_id, requester_id, status, required_role, expires_at
                ) values (
                    :id, :workspaceId, :runId, :proposalId, :requester, 'PENDING', :role, now() + (:ttl * interval '1 second')
                )
                """, new MapSqlParameterSource()
                .addValue("id", approvalId)
                .addValue("workspaceId", run.workspaceId())
                .addValue("runId", runId)
                .addValue("proposalId", proposalId)
                .addValue("requester", run.userId())
                .addValue("role", decision.requiredRole())
                .addValue("ttl", ttl == null ? 900 : ttl));
        if (!transition(runId, "APPROVAL_REQUIRED")) {
            return;
        }
        events.append(runId, "APPROVAL_REQUIRED", "APPROVAL_REQUIRED", Map.of(
                "approvalId", approvalId, "proposalId", proposalId, "requiredRole", decision.requiredRole()));
        audit.record(run.workspaceId(), run.userId(), "APPROVAL_REQUESTED", "approval", approvalId.toString(), runId, Map.of(
                "tool", toolName, "requiredRole", decision.requiredRole()));
    }

    public Map<String, Object> decide(Actor actor, UUID workspaceId, UUID approvalId, boolean approve, String reason) {
        var membership = actor.membership(workspaceId);
        if (membership == null || !ApprovalAccess.canDecide(membership.role())) {
            throw new ApiException("FORBIDDEN", "Your role cannot decide approvals.", 403);
        }
        String trimmedReason = reason == null ? "" : reason.trim();
        if (!approve) {
            if (trimmedReason.isEmpty()) {
                throw new ApiException("VALIDATION_FAILED", "A rejection reason is required.", 400);
            }
            if (trimmedReason.length() > 2000) {
                throw new ApiException("VALIDATION_FAILED", "The rejection reason is too long.", 400);
            }
        }
        UUID[] expiredRun = new UUID[1];
        tx.executeWithoutResult(status -> {
            var rows = jdbc.query("""
                    select a.status, a.required_role, a.expires_at, a.run_id, a.proposal_id, a.requester_id,
                           p.tool_name, p.arguments::text as arguments
                    from approvals a join tool_proposals p on p.id = a.proposal_id
                    where a.id = :id and a.workspace_id = :workspace for update of a
                    """, Map.of("id", approvalId, "workspace", workspaceId), (rs, n) -> new ApprovalRow(
                    rs.getString("status"),
                    rs.getString("required_role"),
                    rs.getTimestamp("expires_at").toInstant(),
                    UUID.fromString(rs.getString("run_id")),
                    UUID.fromString(rs.getString("proposal_id")),
                    UUID.fromString(rs.getString("requester_id")),
                    rs.getString("tool_name"),
                    rs.getString("arguments")
            ));
            if (rows.isEmpty()) {
                throw new ApiException("NOT_FOUND", "Approval not found.", 404);
            }
            ApprovalRow row = rows.get(0);
            String state = row.status();
            UUID runId = row.runId();
            if (!"PENDING".equals(state)) {
                if (ApprovalStateMachine.isIdempotentReplay(state, approve)) {
                    return;
                }
                throw new ApiException(ApprovalStateMachine.conflictCode(state),
                        ApprovalStateMachine.conflictMessage(state, approve), 409, runId);
            }
            if (row.expiresAt().isBefore(Instant.now())) {
                int expired = jdbc.update("""
                        update approvals set status = 'EXPIRED', decided_at = now()
                        where id = :id and status = 'PENDING'
                        """, Map.of("id", approvalId));
                if (expired == 1) {
                    events.append(runId, "APPROVAL_EXPIRED", "APPROVAL_REQUIRED", Map.of("approvalId", approvalId));
                    audit.record(workspaceId, actor.id(), "APPROVAL_EXPIRED", "approval", approvalId.toString(), runId, Map.of());
                }
                expiredRun[0] = runId;
                return;
            }
            if ("ADMIN".equals(row.requiredRole()) && !"ADMIN".equals(membership.role())) {
                throw new ApiException("FORBIDDEN", "This approval requires an admin.", 403);
            }
            if (!ApprovalAccess.canDecideThis(membership.role(), actor.id(), row.requesterId())) {
                throw new ApiException("FORBIDDEN", "You cannot approve or reject an action you requested.", 403);
            }
            String runState = jdbc.queryForObject(
                    "select state from agent_runs where id = :id for update", Map.of("id", runId), String.class);
            if (!"APPROVAL_REQUIRED".equals(runState)) {
                throw new ApiException("CONFLICT", "The run is no longer executable.", 409, runId);
            }
            String nextStatus = approve ? "APPROVED" : "REJECTED";
            int changed = jdbc.update("""
                    update approvals
                    set status = :status, reviewer_id = :reviewer, decision_reason = :reason, decided_at = now()
                    where id = :id and status = 'PENDING' and expires_at > now()
                    """, Map.of("id", approvalId, "status", nextStatus, "reviewer", actor.id(),
                    "reason", trimmedReason));
            if (changed != 1) {
                throw new ApiException("CONFLICT", "The approval is no longer pending.", 409, runId);
            }
            if (approve) {
                if (!transition(runId, "APPROVED") || !transition(runId, "TOOL_EXECUTING")) {
                    throw new ApiException("CONFLICT", "The run can no longer be approved.", 409, runId);
                }
                var payload = new LinkedHashMap<String, Object>();
                payload.put("runId", runId);
                payload.put("proposalId", row.proposalId());
                payload.put("approvalId", approvalId);
                payload.put("workspaceId", workspaceId);
                payload.put("tool", row.toolName());
                putTrace(payload, runId);
                payload.put("arguments", Jsons.map(mapper, row.arguments()));
                jdbc.update("""
                        insert into jobs (id, workspace_id, job_type, payload, status, max_attempts, idempotency_key)
                        values (:id, :workspace, 'EXECUTE_TOOL', :payload, 'PENDING', 5, :key)
                        on conflict (idempotency_key) do nothing
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("workspace", workspaceId)
                        .addValue("payload", Jsons.jsonb(Jsons.write(mapper, payload)))
                        .addValue("key", "tool-exec:" + row.proposalId()));
                events.append(runId, "APPROVAL_APPROVED", "TOOL_EXECUTING", Map.of("approvalId", approvalId, "proposalId", row.proposalId()));
                events.append(runId, "TOOL_STARTED", "TOOL_EXECUTING", Map.of("tool", row.toolName()));
                audit.record(workspaceId, actor.id(), "APPROVAL_APPROVED", "approval", approvalId.toString(), runId, Map.of(
                        "tool", row.toolName(), "proposalId", row.proposalId().toString(), "reason", trimmedReason));
            } else {
                if (!transition(runId, "REJECTED")) {
                    throw new ApiException("CONFLICT", "The run can no longer be rejected.", 409, runId);
                }
                events.append(runId, "APPROVAL_REJECTED", "REJECTED", Map.of("approvalId", approvalId, "reason", trimmedReason));
                audit.record(workspaceId, actor.id(), "APPROVAL_REJECTED", "approval", approvalId.toString(), runId, Map.of(
                        "reason", trimmedReason, "proposalId", row.proposalId().toString()));
                String draft = jdbc.queryForObject("select coalesce(draft_answer, '') from agent_runs where id = :id",
                        Map.of("id", runId), String.class);
                complete(runId, draft + "\n\nA reviewer declined the proposed support ticket. No ticket was created.", null);
            }
        });
        if (expiredRun[0] != null) {
            fail(expiredRun[0], "APPROVAL_EXPIRED", "APPROVAL_EXPIRED", "The approval expired before a decision.");
            throw new ApiException("APPROVAL_EXPIRED", "The approval has expired.", 409, expiredRun[0]);
        }
        return approvals.get(actor, workspaceId, approvalId);
    }

    public void toolResult(UUID runId, Map<String, Object> body) {
        tx.executeWithoutResult(status -> {
            String state = jdbc.queryForObject("select state from agent_runs where id = :id for update", Map.of("id", runId), String.class);
            if (RunStateMachine.isTerminal(state)) {
                return;
            }
            String resultStatus = String.valueOf(body.getOrDefault("status", "FAILED"));
            if ("SUCCEEDED".equals(resultStatus)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = body.get("result") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
                String draft = jdbc.queryForObject("select coalesce(draft_answer, '') from agent_runs where id = :id",
                        Map.of("id", runId), String.class);
                String ticketId = String.valueOf(result.getOrDefault("ticketId", ""));
                events.append(runId, "TOOL_COMPLETED", "TOOL_EXECUTING", Map.of("status", "SUCCEEDED", "ticketId", ticketId));
                complete(runId, draft + "\n\nA reviewer approved support ticket " + ticketId + ". It was created once for this run.", null);
                UUID workspaceId = jdbc.queryForObject("select workspace_id from agent_runs where id = :id", Map.of("id", runId), UUID.class);
                audit.record(workspaceId, null, "TOOL_EXECUTED", "ticket", ticketId, runId, Map.of("status", "SUCCEEDED"));
            } else if ("SKIPPED".equals(resultStatus)) {
                String draft = jdbc.queryForObject("select coalesce(draft_answer, '') from agent_runs where id = :id",
                        Map.of("id", runId), String.class);
                events.append(runId, "TOOL_COMPLETED", state, Map.of("status", "SKIPPED", "errorType", String.valueOf(body.get("errorType"))));
                complete(runId, draft + "\n\nNo ticket was created.", null);
            } else {
                events.append(runId, "TOOL_COMPLETED", state, Map.of("status", resultStatus, "errorType", String.valueOf(body.get("errorType"))));
                fail(runId, "TOOL_ERROR", String.valueOf(body.getOrDefault("errorType", "TOOL_ERROR")), "The tool did not complete.");
            }
        });
    }

    public Map<String, Object> cancel(Actor actor, UUID workspaceId, UUID runId) {
        var membership = actor.membership(workspaceId);
        RunRow run = lockless(runId);
        if (!run.workspaceId().equals(workspaceId)) {
            throw new ApiException("NOT_FOUND", "Run not found.", 404);
        }
        boolean owner = run.userId().equals(actor.id()) && "OPERATOR".equals(membership.role());
        if (!owner && !"ADMIN".equals(membership.role())) {
            throw new ApiException("FORBIDDEN", "You cannot cancel this run.", 403);
        }
        tx.executeWithoutResult(status -> {
            if (!transition(runId, "CANCELLED")) {
                throw new ApiException("CONFLICT", "The run is already finished.", 409, runId);
            }
            List<UUID> cancelled = closePendingApprovals(runId, "CANCELLED");
            jdbc.update("""
                    update agent_runs set ended_at = now(), failure_category = 'CANCELLED', error_code = 'CANCELLED',
                        error_message = 'The run was cancelled.'
                    where id = :id
                    """, Map.of("id", runId));
            events.append(runId, "RUN_CANCELLED", "CANCELLED", Map.of());
            audit.record(workspaceId, actor.id(), "RUN_CANCELLED", "agent_run", runId.toString(), runId, Map.of());
            for (UUID approvalId : cancelled) {
                events.append(runId, "APPROVAL_CANCELLED", "CANCELLED", Map.of("approvalId", approvalId));
                audit.record(workspaceId, actor.id(), "APPROVAL_CANCELLED", "approval", approvalId.toString(), runId, Map.of());
            }
        });
        return execution(actor, workspaceId, runId);
    }

    public Map<String, Object> get(Actor actor, UUID workspaceId, UUID runId) {
        ensureVisible(actor, workspaceId, runId);
        return runView(runId);
    }

    public Map<String, Object> execution(Actor actor, UUID workspaceId, UUID runId) {
        ensureVisible(actor, workspaceId, runId);
        var membership = actor.membership(workspaceId);
        String role = membership.role();
        Map<String, Object> run = executionRun(runId);
        UUID ownerId = (UUID) run.get("userId");
        String state = String.valueOf(run.get("state"));
        boolean includeArguments = RunVisibility.includeProposalArguments(role);
        var body = new LinkedHashMap<String, Object>();
        body.put("run", run);
        body.put("events", events(actor, workspaceId, runId));
        body.put("proposal", proposalView(runId, includeArguments));
        body.put("approval", approvalSummary(runId));
        body.put("capabilities", Map.of(
                "canCancel", RunVisibility.canCancel(role, actor.id(), ownerId, state),
                "canReadApprovals", RunVisibility.canReadApprovals(role),
                "includeProposalArguments", includeArguments
        ));
        return body;
    }

    public Map<String, Object> list(Actor actor, UUID workspaceId, int page, int size) {
        int limit = Math.min(Math.max(size, 1), 100);
        int offset = Math.max(page, 0) * limit;
        String filter = visibilitySql(actor.membership(workspaceId).role());
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("user", actor.id())
                .addValue("limit", limit)
                .addValue("offset", offset);
        List<Map<String, Object>> items = jdbc.query("""
                select r.id, r.state, r.created_at, r.started_at, r.ended_at, r.failure_category, r.model,
                       r.estimated_cost_usd, r.user_id
                from agent_runs r
                where r.workspace_id = :workspace
                """ + filter + """
                order by r.created_at desc limit :limit offset :offset
                """, params, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("state", rs.getString("state"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("failureCategory", rs.getString("failure_category"));
            row.put("model", rs.getString("model"));
            row.put("estimatedCostUsd", rs.getBigDecimal("estimated_cost_usd"));
            row.put("userId", UUID.fromString(rs.getString("user_id")));
            return row;
        });
        Long total = jdbc.queryForObject("select count(*) from agent_runs r where r.workspace_id = :workspace" + filter, params, Long.class);
        return Map.of("items", items, "page", Math.max(page, 0), "size", limit, "total", total == null ? 0 : total);
    }

    public List<Map<String, Object>> events(Actor actor, UUID workspaceId, UUID runId) {
        ensureVisible(actor, workspaceId, runId);
        return jdbc.query("""
                select sequence, event_type, state, payload::text as payload, created_at
                from run_events where run_id = :id order by sequence
                """, Map.of("id", runId), (rs, n) -> Map.of(
                "sequence", rs.getInt("sequence"),
                "eventType", rs.getString("event_type"),
                "state", rs.getString("state"),
                "payload", Jsons.map(mapper, rs.getString("payload")),
                "createdAt", rs.getTimestamp("created_at").toInstant().toString()
        ));
    }

    public SseEmitter stream(Actor actor, UUID workspaceId, UUID runId, String lastEventId) {
        ensureVisible(actor, workspaceId, runId);
        return events.subscribe(runId, lastEventId);
    }

    public Map<String, Object> approvalView(UUID approvalId) {
        return jdbc.queryForObject("""
                select a.id, a.status, a.required_role, a.decision_reason, a.requested_at, a.decided_at, a.expires_at,
                       a.run_id, a.requester_id, a.reviewer_id, p.tool_name, p.arguments::text as arguments,
                       p.reason, p.risk_level, p.policy_decision, p.policy_code, r.trace_id, u.email as requester_email
                from approvals a
                join tool_proposals p on p.id = a.proposal_id
                join agent_runs r on r.id = a.run_id
                join users u on u.id = a.requester_id
                where a.id = :id
                """, Map.of("id", approvalId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("status", rs.getString("status"));
            row.put("requiredRole", rs.getString("required_role"));
            row.put("decisionReason", rs.getString("decision_reason"));
            row.put("requestedAt", rs.getTimestamp("requested_at").toInstant().toString());
            row.put("decidedAt", rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant().toString());
            row.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
            row.put("runId", UUID.fromString(rs.getString("run_id")));
            row.put("traceId", rs.getString("trace_id"));
            row.put("requesterId", UUID.fromString(rs.getString("requester_id")));
            row.put("requesterEmail", rs.getString("requester_email"));
            row.put("reviewerId", rs.getString("reviewer_id") == null ? null : UUID.fromString(rs.getString("reviewer_id")));
            row.put("tool", rs.getString("tool_name"));
            row.put("arguments", Jsons.map(mapper, rs.getString("arguments")));
            row.put("reason", rs.getString("reason"));
            row.put("risk", rs.getString("risk_level"));
            row.put("policyDecision", rs.getString("policy_decision"));
            row.put("policyCode", rs.getString("policy_code"));
            return row;
        });
    }

    private RuntimeClient.Plan callRuntime(RunRow run, Map<String, Object> snapshot) {
        var body = new LinkedHashMap<String, Object>();
        body.put("runId", run.id());
        body.put("traceId", run.traceId());
        body.put("workspaceId", run.workspaceId());
        body.put("question", run.question());
        body.put("knowledgeBaseId", snapshot.get("knowledgeBaseId"));
        body.put("knowledgeBaseVersionId", snapshot.get("knowledgeBaseVersionId"));
        body.put("embeddingModel", snapshot.get("embeddingModel"));
        body.put("systemPrompt", snapshot.get("systemPrompt"));
        body.put("provider", snapshot.get("provider"));
        body.put("model", snapshot.get("model"));
        body.put("temperature", snapshot.get("temperature"));
        body.put("maxTokens", snapshot.get("maxTokens"));
        body.put("allowedTools", snapshot.get("tools"));
        body.put("simulate", snapshot.get("simulate"));
            int attempts = 0;
        while (true) {
            attempts++;
            var child = tracer.nextSpan().name("aegistrace.runtime.plan").start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(child)) {
                child.tag("run.id", run.id().toString());
                child.tag("workspace.id", run.workspaceId().toString());
                if (snapshot.get("knowledgeBaseVersionId") != null) {
                    child.tag("knowledge.version_id", String.valueOf(snapshot.get("knowledgeBaseVersionId")));
                }
                return runtime.plan(body, ((Number) snapshot.get("timeoutMs")).intValue());
            } catch (RuntimeClient.RuntimeCallException ex) {
                jdbc.update("update agent_runs set orchestration_attempts = orchestration_attempts + 1 where id = :id",
                        Map.of("id", run.id()));
                events.append(run.id(), "MODEL_COMPLETED", runState(run.id()), Map.of(
                        "retry", true, "attempt", attempts, "errorType", ex.getCategory()));
                meters.counter("aegis.job.retries", "kind", "model").increment();
                boolean expired = Instant.now().isAfter(run.timeoutAt());
                if (!ex.isRetryable() || attempts >= 3 || expired) {
                    fail(run.id(), expired ? "TIMED_OUT" : ex.getCategory(), ex.getCode(), ex.getMessage());
                    return null;
                }
                try {
                    Thread.sleep(Math.min(60_000L, 1L << attempts) * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(run.id(), "CANCELLED", "CANCELLED", "The run was interrupted.");
                    return null;
                }
            } finally {
                child.end();
            }
        }
    }

    private PolicyDecision evaluateTool(RunRow run, Map<String, Object> snapshot, String toolName,
                                       Map<String, Object> arguments, int priorCalls, boolean budgetExceeded) {
        var span = tracer.nextSpan().name("aegistrace.policy.evaluate").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            span.tag("run.id", run.id().toString());
            span.tag("tool.name", toolName);
            var tools = jdbc.query("""
                select t.name, t.classification, t.approval_required, t.required_permission
                from tools t where t.name = :name
                """, Map.of("name", toolName), (rs, n) -> new ToolDef(
                rs.getString("name"), rs.getString("classification"), rs.getBoolean("approval_required"), rs.getString("required_permission")));
        boolean registered = !tools.isEmpty();
        @SuppressWarnings("unchecked")
        List<String> allowed = snapshot.get("tools") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : List.of();
        var validation = ArgumentValidator.validate(toolName, arguments);
        String role = jdbc.queryForObject(
                "select role from memberships where user_id = :user and workspace_id = :workspace",
                Map.of("user", run.userId(), "workspace", run.workspaceId()), String.class);
        String priority = arguments.get("priority") instanceof String value ? value : null;
        int maxCalls = ((Number) snapshot.getOrDefault("maxToolCalls", 3)).intValue();
        PolicyDecision decision = policy.evaluate(new PolicyEngine.PolicyRequest(
                toolName,
                arguments,
                registered,
                registered && allowed.contains(toolName),
                !registered || validation.valid(),
                validation.error(),
                registered && PolicyEngine.permissionAllows(role, tools.get(0).permission()),
                priorCalls,
                maxCalls,
                budgetExceeded,
                registered ? tools.get(0).classification() : "WRITE",
                registered && tools.get(0).approvalRequired(),
                priority
        ));
            span.tag("policy.decision", decision.kind().name());
            span.tag("policy.code", decision.code() == null ? "" : decision.code());
            return decision;
        } finally {
            span.end();
        }
    }

    private boolean transition(UUID runId, String to) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            String from = jdbc.queryForObject("select state from agent_runs where id = :id for update", Map.of("id", runId), String.class);
            if (!RunStateMachine.canTransition(from, to)) {
                return false;
            }
            jdbc.update("update agent_runs set state = :to where id = :id", Map.of("to", to, "id", runId));
            return true;
        }));
    }

    private void complete(UUID runId, String answer, String category) {
        Boolean changed = tx.execute(status -> {
            String state = jdbc.queryForObject("select state from agent_runs where id = :id for update", Map.of("id", runId), String.class);
            if (RunStateMachine.isTerminal(state) || !RunStateMachine.canTransition(state, "COMPLETED")) {
                return false;
            }
            jdbc.update("""
                    update agent_runs set state = 'COMPLETED', final_response = :answer, ended_at = now(), failure_category = :category
                    where id = :id
                    """, new MapSqlParameterSource().addValue("answer", answer).addValue("category", category).addValue("id", runId));
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            events.append(runId, "RUN_COMPLETED", "COMPLETED", Map.of("category", category == null ? "" : category));
            enqueueEvaluation(runId);
            meters.counter("aegis.runs", "result", "completed").increment();
        }
    }

    private void fail(UUID runId, String category, String code, String message) {
        String target = "TIMED_OUT".equals(category) ? "TIMED_OUT" : "FAILED";
        List<UUID> closed = new ArrayList<>();
        Boolean changed = tx.execute(status -> {
            String state = jdbc.queryForObject("select state from agent_runs where id = :id for update", Map.of("id", runId), String.class);
            if (RunStateMachine.isTerminal(state) || !RunStateMachine.canTransition(state, target)) {
                return false;
            }
            jdbc.update("""
                    update agent_runs set state = :state, failure_category = :category, error_code = :code,
                        error_message = :message, ended_at = now()
                    where id = :id
                    """, new MapSqlParameterSource()
                    .addValue("state", target)
                    .addValue("category", category)
                    .addValue("code", code)
                    .addValue("message", message)
                    .addValue("id", runId));
            closed.addAll(closePendingApprovals(runId, "CANCELLED"));
            return true;
        });
        if (Boolean.TRUE.equals(changed)) {
            events.append(runId, "TIMED_OUT".equals(target) ? "RUN_TIMED_OUT" : "RUN_FAILED", target, Map.of(
                    "category", category, "code", code));
            meters.counter("aegis.runs", "result", target.toLowerCase()).increment();
            UUID workspaceId = jdbc.queryForObject("select workspace_id from agent_runs where id = :id", Map.of("id", runId), UUID.class);
            for (UUID approvalId : closed) {
                events.append(runId, "APPROVAL_CANCELLED", target, Map.of("approvalId", approvalId));
                audit.record(workspaceId, null, "APPROVAL_CANCELLED", "approval", approvalId.toString(), runId, Map.of("runFailure", code));
            }
        }
    }

    private List<UUID> closePendingApprovals(UUID runId, String toStatus) {
        List<UUID> ids = jdbc.query(
                "select id from approvals where run_id = :id and status = 'PENDING' for update",
                Map.of("id", runId), (rs, n) -> UUID.fromString(rs.getString("id")));
        if (ids.isEmpty()) {
            return ids;
        }
        jdbc.update("""
                update approvals set status = :status, decided_at = now()
                where run_id = :id and status = 'PENDING'
                """, Map.of("id", runId, "status", toStatus));
        return ids;
    }

    private void putTrace(Map<String, Object> payload, UUID runId) {
        var rows = jdbc.query("select trace_id, span_id from agent_runs where id = :id", Map.of("id", runId),
                (rs, n) -> new String[]{rs.getString("trace_id"), rs.getString("span_id")});
        if (rows.isEmpty()) {
            return;
        }
        if (rows.get(0)[0] != null) {
            payload.put("traceId", rows.get(0)[0]);
        }
        if (rows.get(0)[1] != null) {
            payload.put("spanId", rows.get(0)[1]);
        }
    }

    private void enqueueEvaluation(UUID runId) {
        var span = tracer.nextSpan().name("aegistrace.evaluation.enqueue").start();
        try {
            UUID workspaceId = jdbc.queryForObject("select workspace_id from agent_runs where id = :id", Map.of("id", runId), UUID.class);
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("runId", runId);
            putTrace(payload, runId);
            jdbc.update("""
                    insert into jobs (id, workspace_id, job_type, payload, status, max_attempts, idempotency_key)
                    values (:id, :workspace, 'EVALUATE_RUN', :payload, 'PENDING', 3, :key)
                    on conflict (idempotency_key) do nothing
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("workspace", workspaceId)
                    .addValue("payload", Jsons.jsonb(Jsons.write(mapper, payload)))
                    .addValue("key", "eval:" + runId));
            span.tag("run.id", runId.toString());
        } finally {
            span.end();
        }
    }

    public static String visibilitySql(String role) {
        return switch (role) {
            case "OPERATOR" -> " and r.user_id = :user ";
            case "REVIEWER" -> " and exists (select 1 from approvals vis where vis.run_id = r.id) ";
            default -> " ";
        };
    }

    private void ensureVisible(Actor actor, UUID workspaceId, UUID runId) {
        var membership = actor.membership(workspaceId);
        if (membership == null) {
            throw new ApiException("FORBIDDEN", "You are not a member of that workspace.", 403);
        }
        var rows = jdbc.query("""
                select user_id, workspace_id,
                       exists (select 1 from approvals vis where vis.run_id = agent_runs.id) as has_approval
                from agent_runs where id = :id
                """, Map.of("id", runId), (rs, n) -> new Object[]{
                UUID.fromString(rs.getString("user_id")),
                UUID.fromString(rs.getString("workspace_id")),
                rs.getBoolean("has_approval")
        });
        if (rows.isEmpty()) {
            throw new ApiException("NOT_FOUND", "Run not found.", 404);
        }
        RunVisibility.assertVisible(membership.role(), actor.id(), (UUID) rows.get(0)[0], workspaceId,
                (UUID) rows.get(0)[1], (Boolean) rows.get(0)[2]);
    }

    private Map<String, Object> executionRun(UUID runId) {
        return jdbc.queryForObject("""
                select r.id, r.workspace_id, r.user_id, r.agent_id, r.agent_version_id, r.prompt_version_id, r.state,
                       r.failure_category, r.error_code, r.error_message, r.question, r.draft_answer, r.final_response,
                       r.citations::text as citations, r.input_tokens, r.output_tokens, r.estimated_cost_usd,
                       r.provider, r.model, r.trace_id, r.request_id, r.started_at, r.ended_at, r.timeout_at, r.created_at,
                       a.name as agent_name, av.version_number
                from agent_runs r
                join agents a on a.id = r.agent_id
                join agent_versions av on av.id = r.agent_version_id
                where r.id = :id
                """, Map.of("id", runId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            Instant created = rs.getTimestamp("created_at").toInstant();
            Instant started = rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant();
            Instant ended = rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant();
            Instant timeout = rs.getTimestamp("timeout_at").toInstant();
            Instant durationEnd = ended != null ? ended : Instant.now();
            Instant durationStart = started != null ? started : created;
            Long durationMs = durationEnd.isBefore(durationStart) ? null : java.time.Duration.between(durationStart, durationEnd).toMillis();
            int input = rs.getInt("input_tokens");
            int output = rs.getInt("output_tokens");
            String model = rs.getString("model");
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("workspaceId", UUID.fromString(rs.getString("workspace_id")));
            row.put("userId", UUID.fromString(rs.getString("user_id")));
            row.put("agentId", UUID.fromString(rs.getString("agent_id")));
            row.put("agentVersionId", UUID.fromString(rs.getString("agent_version_id")));
            row.put("promptVersionId", UUID.fromString(rs.getString("prompt_version_id")));
            row.put("agentName", rs.getString("agent_name"));
            row.put("agentVersionNumber", rs.getInt("version_number"));
            row.put("state", rs.getString("state"));
            row.put("failureCategory", rs.getString("failure_category"));
            row.put("errorCode", rs.getString("error_code"));
            row.put("errorMessage", rs.getString("error_message"));
            row.put("question", rs.getString("question"));
            row.put("draftAnswer", rs.getString("draft_answer"));
            row.put("finalResponse", rs.getString("final_response"));
            row.put("citations", mapper.convertValue(readJson(rs.getString("citations"), List.of()), new TypeReference<List<Object>>() {}));
            row.put("inputTokens", input);
            row.put("outputTokens", output);
            row.put("provider", rs.getString("provider"));
            row.put("model", model);
            row.put("traceId", rs.getString("trace_id"));
            row.put("requestId", rs.getString("request_id"));
            row.put("startedAt", started == null ? null : started.toString());
            row.put("endedAt", ended == null ? null : ended.toString());
            row.put("timeoutAt", timeout.toString());
            row.put("createdAt", created.toString());
            row.put("durationMs", durationMs);
            row.put("cost", runCost(model, input, output, rs.getBigDecimal("estimated_cost_usd")));
            return row;
        });
    }

    private Map<String, Object> proposalView(UUID runId, boolean includeArguments) {
        var rows = jdbc.query("""
                select p.id, p.tool_name, t.classification, p.arguments::text as arguments, p.reason, p.risk_level,
                       p.policy_decision, p.policy_code, p.policy_reason
                from tool_proposals p
                left join tools t on t.name = p.tool_name
                where p.run_id = :id
                order by p.sequence desc
                limit 1
                """, Map.of("id", runId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("tool", rs.getString("tool_name"));
            row.put("classification", rs.getString("classification"));
            row.put("risk", rs.getString("risk_level"));
            row.put("policyDecision", rs.getString("policy_decision"));
            row.put("policyCode", rs.getString("policy_code"));
            row.put("policyReason", rs.getString("policy_reason"));
            if (includeArguments) {
                row.put("arguments", Jsons.map(mapper, rs.getString("arguments")));
                row.put("reason", rs.getString("reason"));
            }
            return row;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> approvalSummary(UUID runId) {
        var rows = jdbc.query("""
                select id, status, required_role, expires_at
                from approvals where run_id = :id
                order by requested_at desc
                limit 1
                """, Map.of("id", runId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("status", rs.getString("status"));
            row.put("requiredRole", rs.getString("required_role"));
            row.put("expiresAt", rs.getTimestamp("expires_at").toInstant().toString());
            return row;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> runView(UUID runId) {
        return jdbc.queryForObject("""
                select id, workspace_id, user_id, agent_id, agent_version_id, prompt_version_id, state, failure_category,
                       error_code, error_message, question, draft_answer, final_response, citations::text as citations,
                       input_tokens, output_tokens, estimated_cost_usd, provider, model, trace_id, request_id,
                       started_at, ended_at, timeout_at, created_at, snapshot::text as snapshot
                from agent_runs where id = :id
                """, Map.of("id", runId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("workspaceId", UUID.fromString(rs.getString("workspace_id")));
            row.put("userId", UUID.fromString(rs.getString("user_id")));
            row.put("agentId", UUID.fromString(rs.getString("agent_id")));
            row.put("agentVersionId", UUID.fromString(rs.getString("agent_version_id")));
            row.put("promptVersionId", UUID.fromString(rs.getString("prompt_version_id")));
            row.put("state", rs.getString("state"));
            row.put("failureCategory", rs.getString("failure_category"));
            row.put("errorCode", rs.getString("error_code"));
            row.put("errorMessage", rs.getString("error_message"));
            row.put("question", rs.getString("question"));
            row.put("draftAnswer", rs.getString("draft_answer"));
            row.put("finalResponse", rs.getString("final_response"));
            row.put("citations", mapper.convertValue(readJson(rs.getString("citations"), List.of()), new TypeReference<List<Object>>() {}));
            row.put("inputTokens", rs.getInt("input_tokens"));
            row.put("outputTokens", rs.getInt("output_tokens"));
            row.put("estimatedCostUsd", rs.getBigDecimal("estimated_cost_usd"));
            row.put("provider", rs.getString("provider"));
            row.put("model", rs.getString("model"));
            row.put("traceId", rs.getString("trace_id"));
            row.put("requestId", rs.getString("request_id"));
            row.put("startedAt", rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant().toString());
            row.put("endedAt", rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toInstant().toString());
            row.put("timeoutAt", rs.getTimestamp("timeout_at").toInstant().toString());
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            row.put("snapshot", Jsons.map(mapper, rs.getString("snapshot")));
            return row;
        });
    }

    private Object readJson(String json, Object fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String runState(UUID runId) {
        return jdbc.queryForObject("select state from agent_runs where id = :id", Map.of("id", runId), String.class);
    }

    private RunRow lockless(UUID runId) {
        return jdbc.queryForObject("""
                select id, workspace_id, user_id, state, question, snapshot::text as snapshot, trace_id, timeout_at
                from agent_runs where id = :id
                """, Map.of("id", runId), (rs, n) -> new RunRow(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("workspace_id")),
                UUID.fromString(rs.getString("user_id")),
                rs.getString("state"),
                rs.getString("question"),
                rs.getString("snapshot"),
                rs.getString("trace_id"),
                rs.getTimestamp("timeout_at").toInstant()
        ));
    }

    private AgentVersion loadVersion(UUID workspaceId, UUID agentId) {
        var params = new MapSqlParameterSource().addValue("workspace", workspaceId).addValue("agent", agentId);
        String agentFilter = agentId == null ? "" : " and a.id = :agent ";
        var rows = jdbc.query("""
                select a.id as agent_id, av.id, av.prompt_version_id, av.knowledge_base_id, av.provider, av.model,
                       av.snapshot::text as snapshot
                from agents a
                join agent_versions av on av.agent_id = a.id and av.current_version
                where a.workspace_id = :workspace and a.status = 'ACTIVE'
                """ + agentFilter, params, (rs, n) -> new AgentVersion(
                UUID.fromString(rs.getString("agent_id")),
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("prompt_version_id")),
                UUID.fromString(rs.getString("knowledge_base_id")),
                rs.getString("provider"),
                rs.getString("model"),
                Jsons.map(mapper, rs.getString("snapshot"))
        ));
        if (rows.isEmpty()) {
            throw new ApiException("AGENT_INACTIVE", "No active agent version is available.", 409);
        }
        if (agentId == null && rows.size() != 1) {
            throw new ApiException("AGENT_REQUIRED", "agentId is required when more than one agent is active.", 400);
        }
        return rows.get(0);
    }

    public static Map<String, Object> runCost(String model, int inputTokens, int outputTokens, BigDecimal stored) {
        int tokens = inputTokens + outputTokens;
        var body = new LinkedHashMap<String, Object>();
        body.put("tokens", tokens);
        if (tokens <= 0) {
            body.put("pricingStatus", "NO_TOKENS");
            body.put("amount", null);
            return body;
        }
        if (isZeroPricedModel(model)) {
            body.put("pricingStatus", "CONFIGURED_ZERO");
            body.put("amount", BigDecimal.ZERO);
            return body;
        }
        if (isListPricedModel(model)) {
            body.put("pricingStatus", "PRICED");
            body.put("amount", stored);
            return body;
        }
        body.put("pricingStatus", "PRICING_UNAVAILABLE");
        body.put("amount", null);
        return body;
    }

    public static boolean isZeroPricedModel(String model) {
        return "grounded-extractive-v1".equals(model);
    }

    public static boolean isListPricedModel(String model) {
        return "gpt-4o-mini".equals(model);
    }

    static BigDecimal estimateCost(String model, int inputTokens, int outputTokens) {
        double inputRate = isListPricedModel(model) ? 0.15d : 0d;
        double outputRate = isListPricedModel(model) ? 0.60d : 0d;
        return BigDecimal.valueOf((inputTokens * inputRate + outputTokens * outputRate) / 1_000_000d);
    }

    private record RunRow(UUID id, UUID workspaceId, UUID userId, String state, String question, String snapshot,
                          String traceId, Instant timeoutAt) {}

    private record AgentVersion(UUID agentId, UUID id, UUID promptVersionId, UUID knowledgeBaseId, String provider,
                                String model, Map<String, Object> snapshot) {}

    private record ToolDef(String name, String classification, boolean approvalRequired, String permission) {}

    private record ApprovalRow(String status, String requiredRole, Instant expiresAt, UUID runId, UUID proposalId,
                               UUID requesterId, String toolName, String arguments) {}
}

@RestController
@RequestMapping("/api/v1")
class RunController {
    private final RunService runs;
    private final ApprovalService approvals;
    private final Rbac rbac;

    RunController(RunService runs, ApprovalService approvals, Rbac rbac) {
        this.runs = runs;
        this.approvals = approvals;
        this.rbac = rbac;
    }

    @PostMapping("/runs")
    org.springframework.http.ResponseEntity<Map<String, Object>> create(HttpServletRequest request, @RequestBody CreateRun body) {
        var membership = rbac.require(request, "OPERATOR", "ADMIN");
        UUID agentId = body.agentId() == null || body.agentId().isBlank() ? null : UUID.fromString(body.agentId());
        var run = runs.create(rbac.current(), membership.workspaceId(), body.question(), agentId);
        return org.springframework.http.ResponseEntity.status(HttpStatus.ACCEPTED).body(run);
    }

    @GetMapping("/runs")
    Map<String, Object> list(HttpServletRequest request, @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return runs.list(rbac.current(), membership.workspaceId(), page, size);
    }

    @GetMapping("/runs/{id}")
    Map<String, Object> get(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return runs.get(rbac.current(), membership.workspaceId(), id);
    }

    @GetMapping("/runs/{id}/execution")
    Map<String, Object> execution(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return runs.execution(rbac.current(), membership.workspaceId(), id);
    }

    @GetMapping("/runs/{id}/timeline")
    List<Map<String, Object>> timeline(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return runs.events(rbac.current(), membership.workspaceId(), id);
    }

    @GetMapping(value = "/runs/{id}/events", produces = "text/event-stream")
    SseEmitter events(HttpServletRequest request, @PathVariable UUID id,
                      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        var membership = rbac.require(request, "OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN");
        return runs.stream(rbac.current(), membership.workspaceId(), id, lastEventId);
    }

    @PostMapping("/runs/{id}/cancel")
    Map<String, Object> cancel(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "OPERATOR", "ADMIN");
        return runs.cancel(rbac.current(), membership.workspaceId(), id);
    }

    @GetMapping("/approvals")
    Map<String, Object> listApprovals(HttpServletRequest request,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String risk,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String requester,
                                      @RequestParam(required = false) String agent,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        var membership = rbac.require(request, "REVIEWER", "ADMIN");
        return approvals.list(rbac.current(), membership.workspaceId(), status, risk, q, requester, agent, page, size);
    }

    @GetMapping("/approvals/{id}")
    Map<String, Object> getApproval(HttpServletRequest request, @PathVariable UUID id) {
        var membership = rbac.require(request, "REVIEWER", "ADMIN");
        return approvals.get(rbac.current(), membership.workspaceId(), id);
    }

    @PostMapping("/approvals/{id}/approve")
    Map<String, Object> approve(HttpServletRequest request, @PathVariable UUID id, @RequestBody(required = false) Decision body) {
        var membership = rbac.require(request, "REVIEWER", "ADMIN");
        return runs.decide(rbac.current(), membership.workspaceId(), id, true, body == null ? "" : body.reason());
    }

    @PostMapping("/approvals/{id}/reject")
    Map<String, Object> reject(HttpServletRequest request, @PathVariable UUID id, @RequestBody(required = false) Decision body) {
        var membership = rbac.require(request, "REVIEWER", "ADMIN");
        return runs.decide(rbac.current(), membership.workspaceId(), id, false, body == null ? "" : body.reason());
    }

    record CreateRun(String question, String agentId) {}
    record Decision(String reason) {}
}
