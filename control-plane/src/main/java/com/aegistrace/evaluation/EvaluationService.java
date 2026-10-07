package com.aegistrace.evaluation;

import com.aegistrace.audit.AuditService;
import com.aegistrace.common.ApiException;
import com.aegistrace.common.Jsons;
import com.aegistrace.policy.ArgumentValidator;
import com.aegistrace.policy.PolicyDecision;
import com.aegistrace.policy.PolicyEngine;
import com.aegistrace.run.RunService;
import com.aegistrace.security.Actor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class EvaluationService {
    static final int PAGE_SIZE = 25;
    static final Set<String> RESULT_TERMINAL = Set.of("PASS", "FAIL", "ERROR", "SKIPPED", "INCONCLUSIVE", "CANCELLED");
    static final Set<String> EXECUTION_TERMINAL = Set.of("COMPLETED", "PARTIAL", "ERROR", "CANCELLED");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final RunService runs;
    private final Tracer tracer;
    private final PolicyEngine policy = new PolicyEngine();

    public EvaluationService(NamedParameterJdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper mapper,
                             AuditService audit, RunService runs, ObjectProvider<Tracer> tracer) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.mapper = mapper;
        this.audit = audit;
        this.runs = runs;
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    public Map<String, Object> overview(UUID workspaceId) {
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  count(*) as executions,
                  count(*) filter (where status in ('QUEUED','RUNNING')) as active,
                  count(*) filter (where status = 'COMPLETED') as completed
                from evaluation_executions where workspace_id = :workspace
                """, Map.of("workspace", workspaceId));
        Map<String, Object> results = jdbc.queryForMap("""
                select
                  count(*) filter (where r.status in ('PASS','FAIL')) as decisive,
                  count(*) filter (where r.status = 'PASS') as passes,
                  count(*) filter (where r.status = 'FAIL') as failures,
                  count(*) filter (where c.category = 'GROUNDING' and r.status in ('PASS','FAIL')) as grounding_total,
                  count(*) filter (where c.category = 'GROUNDING' and r.status = 'PASS') as grounding_passes,
                  count(*) filter (where c.category = 'CITATION' and r.status in ('PASS','FAIL')) as citation_total,
                  count(*) filter (where c.category = 'CITATION' and r.status = 'PASS') as citation_passes,
                  count(*) filter (where c.category = 'PROMPT_INJECTION' and r.status in ('PASS','FAIL')) as injection_total,
                  count(*) filter (where c.category = 'PROMPT_INJECTION' and r.status = 'PASS') as injection_passes,
                  count(*) filter (where c.category in ('POLICY','TOOL_BEHAVIOR') and r.status in ('PASS','FAIL')) as policy_total,
                  count(*) filter (where c.category in ('POLICY','TOOL_BEHAVIOR') and r.status = 'PASS') as policy_passes,
                  count(*) filter (where c.category = 'ABSTENTION' and r.status in ('PASS','FAIL')) as abstention_total,
                  count(*) filter (where c.category = 'ABSTENTION' and r.status = 'PASS') as abstention_passes
                from evaluation_results r
                join evaluation_executions e on e.id = r.execution_id
                join evaluation_cases c on c.id = r.case_id
                where e.workspace_id = :workspace
                """, Map.of("workspace", workspaceId));
        var body = new LinkedHashMap<String, Object>();
        body.put("generatedAt", Instant.now().toString());
        body.put("executions", count(counts.get("executions")));
        body.put("active", count(counts.get("active")));
        body.put("completed", count(counts.get("completed")));
        body.put("passRate", rate(results, "passes", "decisive",
                "PASS results / (PASS + FAIL results). ERROR, SKIPPED, INCONCLUSIVE, and CANCELLED are excluded."));
        body.put("failureRate", rate(results, "failures", "decisive",
                "FAIL results / (PASS + FAIL results). Infrastructure errors are excluded."));
        body.put("groundingPassRate", rate(results, "grounding_passes", "grounding_total",
                "PASS grounding cases / decisive grounding cases."));
        body.put("citationPassRate", rate(results, "citation_passes", "citation_total",
                "PASS citation cases / decisive citation cases."));
        body.put("policyComplianceRate", rate(results, "policy_passes", "policy_total",
                "PASS policy and tool-behavior cases / decisive cases in those categories."));
        body.put("abstentionCorrectness", rate(results, "abstention_passes", "abstention_total",
                "Correct abstention cases / decisive abstention cases."));
        body.put("promptInjectionResistance", rate(results, "injection_passes", "injection_total",
                "PASS prompt-injection cases / decisive prompt-injection cases."));
        body.put("regressions", regressionCount(workspaceId));
        body.put("recent", executions(workspaceId, 0).get("items"));
        return body;
    }

    public Map<String, Object> cases(UUID workspaceId, String category, int page) {
        int safePage = Math.max(page, 0);
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId)
                .addValue("category", blank(category) ? null : category.trim().toUpperCase())
                .addValue("limit", PAGE_SIZE)
                .addValue("offset", safePage * PAGE_SIZE);
        String where = " where workspace_id = :workspace and (:category::text is null or category = :category) ";
        Long total = jdbc.queryForObject("select count(*) from evaluation_cases" + where, params, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                select id, case_key, version_number, name, description, category, execution_type, input_text,
                       expectations::text as expectations, enabled, fixture_source, created_at
                from evaluation_cases
                """ + where + " order by category, case_key, version_number desc limit :limit offset :offset",
                params, (rs, n) -> {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("id", UUID.fromString(rs.getString("id")));
                    row.put("key", rs.getString("case_key"));
                    row.put("version", rs.getInt("version_number"));
                    row.put("name", rs.getString("name"));
                    row.put("description", rs.getString("description"));
                    row.put("category", rs.getString("category"));
                    row.put("executionType", rs.getString("execution_type"));
                    row.put("input", rs.getString("input_text"));
                    row.put("expectations", Jsons.map(mapper, rs.getString("expectations")));
                    row.put("enabled", rs.getBoolean("enabled"));
                    row.put("fixtureSource", rs.getString("fixture_source"));
                    row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                    return row;
                });
        return page(items, total, safePage);
    }

    public Map<String, Object> createCase(Actor actor, UUID workspaceId, CaseInput body) {
        if (blank(body.name()) || blank(body.category()) || blank(body.input())) {
            throw new ApiException("VALIDATION_FAILED", "Name, category, and input are required.", 400);
        }
        String category = body.category().trim().toUpperCase();
        if (!Set.of("CORRECTNESS", "GROUNDING", "CITATION", "POLICY", "TOOL_BEHAVIOR",
                "ABSTENTION", "SAFETY", "PROMPT_INJECTION").contains(category)) {
            throw new ApiException("VALIDATION_FAILED", "Unsupported evaluation category.", 400);
        }
        validateNoPrivateReasoning(body.expectations());
        String executionType = "POLICY".equals(category) ? "POLICY" : "RUN";
        String key = blank(body.caseKey()) ? slug(body.name()) : body.caseKey().trim();
        Integer version = jdbc.queryForObject("""
                select coalesce(max(version_number), 0) + 1 from evaluation_cases
                where workspace_id = :workspace and case_key = :key
                """, Map.of("workspace", workspaceId, "key", key), Integer.class);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into evaluation_cases (
                    id, workspace_id, case_key, version_number, name, description, category, execution_type,
                    input_text, expectations, enabled, created_by
                ) values (
                    :id, :workspace, :key, :version, :name, :description, :category, :executionType,
                    :input, :expectations, :enabled, :user
                )
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("workspace", workspaceId).addValue("key", key)
                .addValue("version", version == null ? 1 : version).addValue("name", body.name().trim())
                .addValue("description", body.description() == null ? "" : body.description().trim())
                .addValue("category", category).addValue("executionType", executionType)
                .addValue("input", body.input().trim())
                .addValue("expectations", Jsons.jsonb(Jsons.write(mapper,
                        body.expectations() == null ? Map.of() : body.expectations())))
                .addValue("enabled", body.enabled()).addValue("user", actor.id()));
        audit.record(workspaceId, actor.id(), "EVALUATION_CASE_CREATED", "evaluation_case", id.toString(), null,
                Map.of("caseKey", key, "version", version == null ? 1 : version, "category", category));
        return Map.of("id", id, "caseKey", key, "version", version == null ? 1 : version);
    }

    public List<Map<String, Object>> suites(UUID workspaceId) {
        return jdbc.query("""
                select s.id, s.suite_key, s.version_number, s.name, s.description, s.enabled, s.fixture_source,
                       s.created_at, count(sc.case_id) as cases
                from evaluation_suites s
                left join evaluation_suite_cases sc on sc.suite_id = s.id
                where s.workspace_id = :workspace
                group by s.id order by s.created_at desc
                """, Map.of("workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("key", rs.getString("suite_key"));
            row.put("version", rs.getInt("version_number"));
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("enabled", rs.getBoolean("enabled"));
            row.put("fixtureSource", rs.getString("fixture_source"));
            row.put("caseCount", rs.getLong("cases"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
    }

    public Map<String, Object> suite(UUID workspaceId, UUID id) {
        var rows = jdbc.query("""
                select id, suite_key, version_number, name, description, enabled, fixture_source, created_at
                from evaluation_suites where id = :id and workspace_id = :workspace
                """, Map.of("id", id, "workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("key", rs.getString("suite_key"));
            row.put("version", rs.getInt("version_number"));
            row.put("name", rs.getString("name"));
            row.put("description", rs.getString("description"));
            row.put("enabled", rs.getBoolean("enabled"));
            row.put("fixtureSource", rs.getString("fixture_source"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "Evaluation suite not found.", 404);
        var body = new LinkedHashMap<String, Object>(rows.get(0));
        body.put("cases", jdbc.query("""
                select c.id, c.case_key, c.version_number, c.name, c.category, c.execution_type, sc.position
                from evaluation_suite_cases sc join evaluation_cases c on c.id = sc.case_id
                where sc.suite_id = :id order by sc.position
                """, Map.of("id", id), (rs, n) -> Map.of(
                "id", UUID.fromString(rs.getString("id")),
                "key", rs.getString("case_key"),
                "version", rs.getInt("version_number"),
                "name", rs.getString("name"),
                "category", rs.getString("category"),
                "executionType", rs.getString("execution_type"),
                "position", rs.getInt("position"))));
        return body;
    }

    public Map<String, Object> createSuite(Actor actor, UUID workspaceId, SuiteInput body) {
        if (blank(body.name()) || body.caseIds() == null || body.caseIds().isEmpty() || body.caseIds().size() > 50) {
            throw new ApiException("VALIDATION_FAILED", "A name and between 1 and 50 cases are required.", 400);
        }
        String key = blank(body.suiteKey()) ? slug(body.name()) : body.suiteKey().trim();
        Integer version = jdbc.queryForObject("""
                select coalesce(max(version_number), 0) + 1 from evaluation_suites
                where workspace_id = :workspace and suite_key = :key
                """, Map.of("workspace", workspaceId, "key", key), Integer.class);
        UUID id = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            Integer owned = jdbc.queryForObject("""
                    select count(*)::int from evaluation_cases
                    where workspace_id = :workspace and id in (:ids)
                    """, new MapSqlParameterSource().addValue("workspace", workspaceId).addValue("ids", body.caseIds()), Integer.class);
            if (owned == null || owned != body.caseIds().size()) {
                throw new ApiException("VALIDATION_FAILED", "One or more cases do not belong to this workspace.", 400);
            }
            jdbc.update("""
                    insert into evaluation_suites (
                        id, workspace_id, suite_key, version_number, name, description, enabled, created_by
                    ) values (:id, :workspace, :key, :version, :name, :description, :enabled, :user)
                    """, Map.of("id", id, "workspace", workspaceId, "key", key,
                    "version", version == null ? 1 : version, "name", body.name().trim(),
                    "description", body.description() == null ? "" : body.description().trim(),
                    "enabled", body.enabled(), "user", actor.id()));
            int position = 0;
            for (UUID caseId : body.caseIds()) {
                jdbc.update("""
                        insert into evaluation_suite_cases (suite_id, case_id, position)
                        values (:suite, :case, :position)
                        """, Map.of("suite", id, "case", caseId, "position", position++));
            }
        });
        audit.record(workspaceId, actor.id(), "EVALUATION_SUITE_CREATED", "evaluation_suite", id.toString(), null,
                Map.of("suiteKey", key, "version", version == null ? 1 : version, "caseCount", body.caseIds().size()));
        return Map.of("id", id, "suiteKey", key, "version", version == null ? 1 : version);
    }

    public Map<String, Object> start(Actor actor, UUID workspaceId, StartInput body) {
        if (body.suiteId() == null || body.agentVersionId() == null || body.knowledgeVersionId() == null
                || blank(body.requestKey())) {
            throw new ApiException("VALIDATION_FAILED", "Suite, agent version, knowledge version, and request key are required.", 400);
        }
        var target = target(workspaceId, body.suiteId(), body.agentVersionId(), body.knowledgeVersionId());
        var existing = jdbc.query("""
                select id from evaluation_executions where workspace_id = :workspace and request_key = :key
                """, Map.of("workspace", workspaceId, "key", body.requestKey().trim()),
                (rs, n) -> UUID.fromString(rs.getString("id")));
        if (!existing.isEmpty()) return execution(actor, workspaceId, existing.get(0));
        UUID id = UUID.randomUUID();
        var span = tracer.nextSpan().name("aegistrace.evaluation.start").start();
        String traceId = span.context().traceId();
        if (traceId == null || traceId.isBlank() || traceId.chars().allMatch(ch -> ch == '0')) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String storedTrace = traceId;
        boolean[] inserted = {false};
        try {
            tx.executeWithoutResult(status -> {
                int created = jdbc.update("""
                        insert into evaluation_executions (
                            id, workspace_id, suite_id, requested_by, agent_version_id, knowledge_base_version_id,
                            provider, model, evaluator_version, status, request_key, trace_id
                        ) values (
                            :id, :workspace, :suite, :user, :agentVersion, :knowledgeVersion,
                            :provider, :model, :evaluator, 'QUEUED', :requestKey, :trace
                        )
                        on conflict (workspace_id, request_key) do nothing
                        """, new MapSqlParameterSource()
                        .addValue("id", id).addValue("workspace", workspaceId).addValue("suite", body.suiteId())
                        .addValue("user", actor.id()).addValue("agentVersion", body.agentVersionId())
                        .addValue("knowledgeVersion", body.knowledgeVersionId()).addValue("provider", target.provider())
                        .addValue("model", target.model()).addValue("evaluator", EvaluationFixtures.EVALUATOR_VERSION)
                        .addValue("requestKey", body.requestKey().trim()).addValue("trace", storedTrace));
                if (created == 0) {
                    return;
                }
                inserted[0] = true;
                jdbc.update("""
                        insert into evaluation_results (id, execution_id, case_id, status)
                        select gen_random_uuid(), :execution, sc.case_id, 'QUEUED'
                        from evaluation_suite_cases sc join evaluation_cases c on c.id = sc.case_id
                        where sc.suite_id = :suite and c.enabled
                        """, Map.of("execution", id, "suite", body.suiteId()));
                Integer total = jdbc.queryForObject(
                        "select count(*)::int from evaluation_results where execution_id = :id",
                        Map.of("id", id), Integer.class);
                if (total == null || total == 0) {
                    throw new ApiException("VALIDATION_FAILED", "The suite has no enabled cases.", 400);
                }
                var payload = Map.of("evaluationExecutionId", id.toString(), "traceId", storedTrace);
                jdbc.update("""
                        insert into jobs (id, workspace_id, job_type, payload, status, max_attempts, idempotency_key)
                        values (:id, :workspace, 'START_EVALUATION', :payload, 'PENDING', 3, :key)
                        on conflict (idempotency_key) do nothing
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("workspace", workspaceId)
                        .addValue("payload", Jsons.jsonb(Jsons.write(mapper, payload)))
                        .addValue("key", "evaluation-start:" + id));
            });
        } finally {
            span.end();
        }
        if (!inserted[0]) {
            UUID existingId = jdbc.queryForObject("""
                    select id from evaluation_executions where workspace_id = :workspace and request_key = :key
                    """, Map.of("workspace", workspaceId, "key", body.requestKey().trim()), UUID.class);
            return execution(actor, workspaceId, existingId);
        }
        audit.record(workspaceId, actor.id(), "EVALUATION_EXECUTION_STARTED", "evaluation_execution", id.toString(), null,
                Map.of("suiteId", body.suiteId().toString(), "agentVersionId", body.agentVersionId().toString(),
                        "knowledgeVersionId", body.knowledgeVersionId().toString()));
        return execution(actor, workspaceId, id);
    }

    public void dispatch(UUID executionId) {
        var info = jdbc.query("""
                select e.id, e.workspace_id, e.requested_by, e.agent_version_id, e.knowledge_base_version_id, e.status
                from evaluation_executions e where e.id = :id
                """, Map.of("id", executionId), (rs, n) -> new Dispatch(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("workspace_id")),
                UUID.fromString(rs.getString("requested_by")), UUID.fromString(rs.getString("agent_version_id")),
                UUID.fromString(rs.getString("knowledge_base_version_id")), rs.getString("status")));
        if (info.isEmpty() || EXECUTION_TERMINAL.contains(info.get(0).status())) return;
        Dispatch execution = info.get(0);
        jdbc.update("""
                update evaluation_executions set status = 'RUNNING', started_at = coalesce(started_at, now())
                where id = :id and status = 'QUEUED'
                """, Map.of("id", executionId));
        var pending = jdbc.query("""
                select r.id, c.execution_type, c.input_text, c.expectations::text as expectations
                from evaluation_results r join evaluation_cases c on c.id = r.case_id
                where r.execution_id = :id and r.status = 'QUEUED'
                order by c.case_key
                """, Map.of("id", executionId), (rs, n) -> new Pending(
                UUID.fromString(rs.getString("id")), rs.getString("execution_type"), rs.getString("input_text"),
                Jsons.map(mapper, rs.getString("expectations"))));
        for (Pending item : pending) {
            if ("POLICY".equals(item.executionType())) {
                evaluatePolicy(execution, item);
            } else {
                runs.createEvaluationRun(execution.requestedBy(), execution.workspaceId(), execution.agentVersionId(),
                        execution.knowledgeVersionId(), item.id(), item.input());
            }
        }
        finalizeExecution(executionId);
    }

    public void completeResult(UUID resultId, Map<String, Object> body) {
        var rows = jdbc.query("""
                select r.status, r.execution_id, e.workspace_id, r.product_run_id
                from evaluation_results r join evaluation_executions e on e.id = r.execution_id
                where r.id = :id
                """, Map.of("id", resultId), (rs, n) -> new ResultRef(
                rs.getString("status"), UUID.fromString(rs.getString("execution_id")),
                UUID.fromString(rs.getString("workspace_id")),
                rs.getString("product_run_id") == null ? null : UUID.fromString(rs.getString("product_run_id"))));
        if (rows.isEmpty() || RESULT_TERMINAL.contains(rows.get(0).status())) return;
        ResultRef ref = rows.get(0);
        String resultStatus = String.valueOf(body.getOrDefault("status", "ERROR")).toUpperCase();
        if (!Set.of("PASS", "FAIL", "ERROR", "SKIPPED", "INCONCLUSIVE").contains(resultStatus)) resultStatus = "ERROR";
        Object rawScore = body.get("score");
        BigDecimal score = rawScore instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> checks = body.get("checks") instanceof List<?> list
                ? (List<Map<String, Object>>) list : List.of();
        String finalStatus = resultStatus;
        tx.executeWithoutResult(status -> {
            int changed = jdbc.update("""
                    update evaluation_results
                    set status = :status, score = :score, failure_category = :category,
                        explanation = :explanation, completed_at = now()
                    where id = :id and status in ('QUEUED','RUNNING')
                    """, new MapSqlParameterSource().addValue("status", finalStatus).addValue("score", score)
                    .addValue("category", text(body.get("failureCategory")))
                    .addValue("explanation", String.valueOf(body.getOrDefault("explanation", "")))
                    .addValue("id", resultId));
            if (changed == 0) {
                return;
            }
            for (Map<String, Object> check : checks) {
                String checkStatus = String.valueOf(check.getOrDefault("status", "ERROR")).toUpperCase();
                if (!Set.of("PASS", "FAIL", "ERROR", "SKIPPED", "INCONCLUSIVE").contains(checkStatus)) checkStatus = "ERROR";
                Object rawCheckScore = check.get("score");
                BigDecimal checkScore = rawCheckScore instanceof Number number ? BigDecimal.valueOf(number.doubleValue()) : null;
                Object evidence = check.get("evidence") instanceof Map<?, ?> map ? map : Map.of();
                jdbc.update("""
                        insert into evaluation_checks (
                            id, result_id, check_key, status, score, failure_category, explanation, evidence
                        ) values (
                            :id, :result, :key, :status, :score, :category, :explanation, :evidence
                        ) on conflict (result_id, check_key) do nothing
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("result", resultId)
                        .addValue("key", String.valueOf(check.getOrDefault("key", "unknown")))
                        .addValue("status", checkStatus).addValue("score", checkScore)
                        .addValue("category", text(check.get("failureCategory")))
                        .addValue("explanation", String.valueOf(check.getOrDefault("explanation", "No explanation recorded.")))
                        .addValue("evidence", Jsons.jsonb(Jsons.write(mapper, evidence))));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> signals = body.get("signals") instanceof List<?> list
                    ? (List<Map<String, Object>>) list : List.of();
            for (Map<String, Object> signal : signals) {
                insertSignal(ref.workspaceId(), ref.productRunId(), resultId,
                        String.valueOf(signal.getOrDefault("type", "EVALUATION")),
                        String.valueOf(signal.getOrDefault("disposition", "UNKNOWN")),
                        String.valueOf(signal.getOrDefault("severity", "MEDIUM")),
                        signal.get("evidence") instanceof Map<?, ?> map ? map : Map.of());
            }
        });
        if (ref.productRunId() != null) {
            runs.cancelEvaluationRun(ref.productRunId());
        }
        finalizeExecution(ref.executionId());
    }

    public void failExecution(UUID executionId, String errorType) {
        tx.executeWithoutResult(status -> {
            var states = jdbc.query("""
                    select status from evaluation_executions where id = :id for update
                    """, Map.of("id", executionId), (rs, n) -> rs.getString("status"));
            if (states.isEmpty() || EXECUTION_TERMINAL.contains(states.get(0))) {
                return;
            }
            List<UUID> pending = jdbc.query("""
                    select id from evaluation_results
                    where execution_id = :id and status in ('QUEUED','RUNNING')
                    for update
                    """, Map.of("id", executionId), (rs, n) -> UUID.fromString(rs.getString("id")));
            for (UUID resultId : pending) {
                jdbc.update("""
                        insert into evaluation_checks (
                            id, result_id, check_key, status, failure_category, explanation, evidence
                        ) values (
                            :id, :result, 'infrastructure.dispatch', 'ERROR', :category,
                            'Evaluation dispatch exhausted its retry budget.', :evidence
                        ) on conflict (result_id, check_key) do nothing
                        """, new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("result", resultId)
                        .addValue("category", text(errorType) == null ? "DISPATCH_ERROR" : text(errorType))
                        .addValue("evidence", Jsons.jsonb(Jsons.write(mapper,
                                Map.of("errorType", text(errorType) == null ? "DISPATCH_ERROR" : text(errorType))))));
            }
            jdbc.update("""
                    update evaluation_results
                    set status = 'ERROR', score = null, failure_category = :category,
                        explanation = 'Evaluation dispatch exhausted its retry budget.', completed_at = now()
                    where execution_id = :id and status in ('QUEUED','RUNNING')
                    """, Map.of("id", executionId,
                    "category", text(errorType) == null ? "DISPATCH_ERROR" : text(errorType)));
        });
        finalizeExecution(executionId);
    }

    public Map<String, Object> executions(UUID workspaceId, int page) {
        int safePage = Math.max(page, 0);
        var params = new MapSqlParameterSource().addValue("workspace", workspaceId)
                .addValue("limit", PAGE_SIZE).addValue("offset", safePage * PAGE_SIZE);
        Long total = jdbc.queryForObject(
                "select count(*) from evaluation_executions where workspace_id = :workspace", params, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                select e.id, e.status, e.evaluator_version, e.provider, e.model, e.trace_id, e.created_at,
                       e.started_at, e.completed_at, s.name as suite_name, s.version_number as suite_version,
                       av.version_number as agent_version, kv.version_number as knowledge_version,
                       count(r.id) as total_cases,
                       count(r.id) filter (where r.status in ('PASS','FAIL','ERROR','SKIPPED','INCONCLUSIVE','CANCELLED')) as completed_cases,
                       count(r.id) filter (where r.status = 'PASS') as passes,
                       count(r.id) filter (where r.status = 'FAIL') as failures
                from evaluation_executions e
                join evaluation_suites s on s.id = e.suite_id
                join agent_versions av on av.id = e.agent_version_id
                join knowledge_base_versions kv on kv.id = e.knowledge_base_version_id
                left join evaluation_results r on r.execution_id = e.id
                where e.workspace_id = :workspace
                group by e.id, s.name, s.version_number, av.version_number, kv.version_number
                order by e.created_at desc limit :limit offset :offset
                """, params, (rs, n) -> executionSummary(rs));
        return page(items, total, safePage);
    }

    public Map<String, Object> execution(Actor actor, UUID workspaceId, UUID id) {
        var rows = jdbc.query("""
                select e.*, s.name as suite_name, s.suite_key, s.version_number as suite_version,
                       av.version_number as agent_version, kv.version_number as knowledge_version,
                       kb.name as knowledge_name, a.name as agent_name
                from evaluation_executions e
                join evaluation_suites s on s.id = e.suite_id
                join agent_versions av on av.id = e.agent_version_id
                join agents a on a.id = av.agent_id
                join knowledge_base_versions kv on kv.id = e.knowledge_base_version_id
                join knowledge_bases kb on kb.id = kv.knowledge_base_id
                where e.id = :id and e.workspace_id = :workspace
                """, Map.of("id", id, "workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("status", rs.getString("status"));
            row.put("suiteId", UUID.fromString(rs.getString("suite_id")));
            row.put("suiteName", rs.getString("suite_name"));
            row.put("suiteKey", rs.getString("suite_key"));
            row.put("suiteVersion", rs.getInt("suite_version"));
            row.put("agentVersionId", UUID.fromString(rs.getString("agent_version_id")));
            row.put("agentName", rs.getString("agent_name"));
            row.put("agentVersion", rs.getInt("agent_version"));
            row.put("knowledgeVersionId", UUID.fromString(rs.getString("knowledge_base_version_id")));
            row.put("knowledgeName", rs.getString("knowledge_name"));
            row.put("knowledgeVersion", rs.getInt("knowledge_version"));
            row.put("provider", rs.getString("provider"));
            row.put("model", rs.getString("model"));
            row.put("evaluatorVersion", rs.getString("evaluator_version"));
            row.put("traceId", rs.getString("trace_id"));
            row.put("createdAt", instant(rs, "created_at"));
            row.put("startedAt", instant(rs, "started_at"));
            row.put("completedAt", instant(rs, "completed_at"));
            return row;
        });
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "Evaluation execution not found.", 404);
        var body = new LinkedHashMap<String, Object>(rows.get(0));
        List<Map<String, Object>> resultRows = jdbc.query("""
                select r.id, r.status, r.score, r.failure_category, r.explanation, r.product_run_id,
                       r.started_at, r.completed_at, c.case_key, c.version_number, c.name, c.category
                from evaluation_results r join evaluation_cases c on c.id = r.case_id
                where r.execution_id = :id order by c.category, c.case_key
                """, Map.of("id", id), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            UUID resultId = UUID.fromString(rs.getString("id"));
            row.put("id", resultId);
            row.put("status", rs.getString("status"));
            row.put("score", rs.getBigDecimal("score"));
            row.put("failureCategory", rs.getString("failure_category"));
            row.put("explanation", rs.getString("explanation"));
            row.put("runId", rs.getString("product_run_id") == null ? null : UUID.fromString(rs.getString("product_run_id")));
            row.put("caseKey", rs.getString("case_key"));
            row.put("caseVersion", rs.getInt("version_number"));
            row.put("caseName", rs.getString("name"));
            row.put("category", rs.getString("category"));
            row.put("startedAt", instant(rs, "started_at"));
            row.put("completedAt", instant(rs, "completed_at"));
            return row;
        });
        Map<UUID, List<Map<String, Object>>> checksByResult = new HashMap<>();
        jdbc.query("""
                select c.result_id, c.check_key, c.status, c.score, c.failure_category,
                       c.explanation, c.evidence::text as evidence
                from evaluation_checks c
                join evaluation_results r on r.id = c.result_id
                where r.execution_id = :id
                order by c.result_id, c.check_key
                """, Map.of("id", id), rs -> {
            UUID resultId = UUID.fromString(rs.getString("result_id"));
            var check = new LinkedHashMap<String, Object>();
            check.put("key", rs.getString("check_key"));
            check.put("status", rs.getString("status"));
            check.put("score", rs.getBigDecimal("score"));
            check.put("failureCategory", rs.getString("failure_category"));
            check.put("explanation", rs.getString("explanation"));
            check.put("evidence", Jsons.map(mapper, rs.getString("evidence")));
            checksByResult.computeIfAbsent(resultId, ignored -> new ArrayList<>()).add(check);
        });
        resultRows.forEach(row -> row.put("checks",
                checksByResult.getOrDefault((UUID) row.get("id"), List.of())));
        body.put("results", resultRows);
        body.put("progress", Map.of(
                "completed", resultRows.stream().filter(r -> RESULT_TERMINAL.contains(String.valueOf(r.get("status")))).count(),
                "total", resultRows.size()));
        body.put("auditEventIds", jdbc.query("""
                select id from audit_events
                where workspace_id = :workspace and resource_type = 'evaluation_execution' and resource_id = :id
                order by created_at
                """, Map.of("workspace", workspaceId, "id", id.toString()),
                (rs, n) -> UUID.fromString(rs.getString("id"))));
        body.put("capabilities", Map.of(
                "canCancel", EvaluationAccess.canManage(actor.membership(workspaceId).role())
                        && !EXECUTION_TERMINAL.contains(String.valueOf(body.get("status")))));
        return body;
    }

    public Map<String, Object> compare(UUID workspaceId, UUID baselineId, UUID candidateId) {
        Map<String, Object> baseline = comparisonHeader(workspaceId, baselineId);
        Map<String, Object> candidate = comparisonHeader(workspaceId, candidateId);
        boolean compatible = baseline.get("suiteKey").equals(candidate.get("suiteKey"));
        List<Map<String, Object>> cases = compatible ? jdbc.query("""
                select b.case_key, b.name, b.category, b.status as baseline_status, c.status as candidate_status
                from (
                    select ec.case_key, ec.name, ec.category, er.status
                    from evaluation_results er join evaluation_cases ec on ec.id = er.case_id
                    where er.execution_id = :baseline
                ) b
                join (
                    select ec.case_key, er.status
                    from evaluation_results er join evaluation_cases ec on ec.id = er.case_id
                    where er.execution_id = :candidate
                ) c on c.case_key = b.case_key
                order by b.category, b.case_key
                """, Map.of("baseline", baselineId, "candidate", candidateId), (rs, n) -> {
            String before = rs.getString("baseline_status");
            String after = rs.getString("candidate_status");
            String change = "UNCHANGED";
            if ("PASS".equals(before) && "FAIL".equals(after)) change = "REGRESSION";
            else if ("FAIL".equals(before) && "PASS".equals(after)) change = "IMPROVEMENT";
            else if (!before.equals(after)) change = "CHANGED";
            return Map.of("caseKey", rs.getString("case_key"), "name", rs.getString("name"),
                    "category", rs.getString("category"), "baseline", before, "candidate", after, "change", change);
        }) : List.of();
        return Map.of(
                "compatible", compatible,
                "compatibilityReason", compatible ? "Same suite key; stable case keys are comparable."
                        : "Suite keys differ. Results are not configuration-compatible and were not compared.",
                "baseline", baseline,
                "candidate", candidate,
                "cases", cases,
                "regressions", cases.stream().filter(row -> "REGRESSION".equals(row.get("change"))).count(),
                "improvements", cases.stream().filter(row -> "IMPROVEMENT".equals(row.get("change"))).count());
    }

    public Map<String, Object> cancel(Actor actor, UUID workspaceId, UUID id) {
        List<UUID> productRuns = tx.execute(status -> {
            var states = jdbc.query("""
                    select status from evaluation_executions
                    where id = :id and workspace_id = :workspace for update
                    """, Map.of("id", id, "workspace", workspaceId), (rs, n) -> rs.getString("status"));
            if (states.isEmpty()) {
                throw new ApiException("NOT_FOUND", "Evaluation execution not found.", 404);
            }
            if (EXECUTION_TERMINAL.contains(states.get(0))) {
                throw new ApiException("CONFLICT", "The evaluation is already finished.", 409);
            }
            List<UUID> runsToCancel = jdbc.query("""
                    select product_run_id from evaluation_results
                    where execution_id = :id and product_run_id is not null
                      and status in ('QUEUED','RUNNING')
                    for update
                    """, Map.of("id", id), (rs, n) -> UUID.fromString(rs.getString("product_run_id")));
            jdbc.update("""
                    update evaluation_executions set status = 'CANCELLED', completed_at = now()
                    where id = :id
                    """, Map.of("id", id));
            jdbc.update("""
                    update evaluation_results set status = 'CANCELLED', completed_at = now(),
                        explanation = 'Evaluation execution was cancelled.'
                    where execution_id = :id and status in ('QUEUED','RUNNING')
                    """, Map.of("id", id));
            audit.record(workspaceId, actor.id(), "EVALUATION_EXECUTION_CANCELLED",
                    "evaluation_execution", id.toString(), null, Map.of());
            return runsToCancel;
        });
        productRuns.forEach(runs::cancelEvaluationRun);
        return execution(actor, workspaceId, id);
    }

    public Map<String, Object> safetyOverview(Actor actor, UUID workspaceId) {
        String visibility = "OPERATOR".equals(actor.membership(workspaceId).role())
                ? " and s.run_id is not null and exists (select 1 from agent_runs r where r.id = s.run_id and r.user_id = :user) " : "";
        var params = new MapSqlParameterSource().addValue("workspace", workspaceId).addValue("user", actor.id());
        List<Map<String, Object>> counts = jdbc.query("""
                select disposition, count(*) as n from safety_signals s
                where workspace_id = :workspace
                """ + visibility + " group by disposition order by disposition", params,
                (rs, n) -> Map.of("disposition", rs.getString("disposition"), "count", rs.getLong("n")));
        return Map.of(
                "generatedAt", Instant.now().toString(),
                "counts", counts,
                "recent", safetySignals(actor, workspaceId, null, 0).get("items"),
                "definition", "Persisted deterministic evaluation and policy signals. Missing evidence is UNKNOWN, never zero risk.");
    }

    public Map<String, Object> safetySignals(Actor actor, UUID workspaceId, String disposition, int page) {
        int safePage = Math.max(page, 0);
        boolean operator = "OPERATOR".equals(actor.membership(workspaceId).role());
        var params = new MapSqlParameterSource()
                .addValue("workspace", workspaceId).addValue("user", actor.id())
                .addValue("disposition", blank(disposition) ? null : disposition.trim().toUpperCase())
                .addValue("limit", PAGE_SIZE).addValue("offset", safePage * PAGE_SIZE);
        String where = """
                 where s.workspace_id = :workspace
                   and (:disposition::text is null or s.disposition = :disposition)
                """ + (operator ? " and s.run_id is not null and r.user_id = :user " : "");
        String from = " from safety_signals s left join agent_runs r on r.id = s.run_id " + where;
        Long total = jdbc.queryForObject("select count(*)" + from, params, Long.class);
        List<Map<String, Object>> items = jdbc.query("""
                select s.id, s.signal_type, s.disposition, s.severity, s.run_id, s.evaluation_result_id,
                       s.evidence::text as evidence, s.created_at
                """ + from + " order by s.created_at desc limit :limit offset :offset", params, (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("type", rs.getString("signal_type"));
            row.put("disposition", rs.getString("disposition"));
            row.put("severity", rs.getString("severity"));
            row.put("runId", rs.getString("run_id") == null ? null : UUID.fromString(rs.getString("run_id")));
            row.put("evaluationResultId", rs.getString("evaluation_result_id") == null ? null
                    : UUID.fromString(rs.getString("evaluation_result_id")));
            row.put("evidence", Jsons.map(mapper, rs.getString("evidence")));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
        return page(items, total, safePage);
    }

    private void evaluatePolicy(Dispatch execution, Pending item) {
        Map<String, Object> expected = item.expectations();
        String tool = String.valueOf(expected.getOrDefault("tool", ""));
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = expected.get("arguments") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        var tools = jdbc.query("""
                select classification, approval_required, required_permission from tools where name = :name
                """, Map.of("name", tool), (rs, n) -> new Tool(
                rs.getString("classification"), rs.getBoolean("approval_required"), rs.getString("required_permission")));
        boolean registered = !tools.isEmpty();
        var validation = ArgumentValidator.validate(tool, arguments);
        PolicyDecision decision = policy.evaluate(new PolicyEngine.PolicyRequest(
                tool, arguments, registered, bool(expected, "assigned", registered),
                !registered || validation.valid(), validation.error(), bool(expected, "userHasPermission", true),
                integer(expected, "priorToolCalls", 0), integer(expected, "maxToolCalls", 3),
                bool(expected, "budgetExceeded", false), registered ? tools.get(0).classification() : "WRITE",
                registered && tools.get(0).approvalRequired(), arguments.get("priority") instanceof String p ? p : null));
        String expectedDecision = String.valueOf(expected.getOrDefault("expectedPolicy", ""));
        String expectedCode = String.valueOf(expected.getOrDefault("expectedPolicyCode", ""));
        boolean pass = expectedDecision.equals(decision.kind().name())
                && (expectedCode.isBlank() || expectedCode.equals(decision.code()));
        var check = new LinkedHashMap<String, Object>();
        check.put("key", "policy.outcome");
        check.put("status", pass ? "PASS" : "FAIL");
        check.put("score", pass ? 1.0 : 0.0);
        check.put("failureCategory", pass ? null : "POLICY_MISMATCH");
        check.put("explanation", "Compared the authoritative deterministic policy decision with the expected decision.");
        check.put("evidence", Map.of("tool", tool, "actualDecision", decision.kind().name(),
                "actualCode", decision.code(), "expectedDecision", expectedDecision, "expectedCode", expectedCode));
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", pass ? "PASS" : "FAIL");
        outcome.put("score", pass ? 1.0 : 0.0);
        outcome.put("failureCategory", pass ? null : "POLICY_MISMATCH");
        outcome.put("explanation", pass ? "Policy behavior matched the case." : "Policy behavior did not match the case.");
        outcome.put("checks", List.of(check));
        if (decision.denied() || decision.kind().name().startsWith("REQUIRE_")) {
            outcome.put("signals", List.of(Map.of(
                    "type", decision.denied() ? "POLICY_DENIAL" : "APPROVAL_REQUIRED",
                    "disposition", decision.denied() ? "BLOCKED" : "DETECTED",
                    "severity", decision.denied() ? "HIGH" : "MEDIUM",
                    "evidence", Map.of("tool", tool, "policyCode", decision.code()))));
        }
        jdbc.update("""
                update evaluation_results set status = 'RUNNING', started_at = coalesce(started_at, now())
                where id = :id and status = 'QUEUED'
                """, Map.of("id", item.id()));
        completeResult(item.id(), outcome);
    }

    private void finalizeExecution(UUID executionId) {
        Map<String, Object> counts = jdbc.queryForMap("""
                select
                  count(*) as total,
                  count(*) filter (where status in ('PASS','FAIL','ERROR','SKIPPED','INCONCLUSIVE','CANCELLED')) as terminal,
                  count(*) filter (where status = 'ERROR') as errors,
                  count(*) filter (where status in ('PASS','FAIL','SKIPPED','INCONCLUSIVE')) as evaluated
                from evaluation_results where execution_id = :id
                """, Map.of("id", executionId));
        long total = count(counts.get("total"));
        if (total == 0 || count(counts.get("terminal")) != total) return;
        long errors = count(counts.get("errors"));
        long evaluated = count(counts.get("evaluated"));
        String status = errors == 0 ? "COMPLETED" : evaluated == 0 ? "ERROR" : "PARTIAL";
        int changed = jdbc.update("""
                update evaluation_executions set status = :status, completed_at = now()
                where id = :id and status in ('QUEUED','RUNNING')
                """, Map.of("status", status, "id", executionId));
        if (changed == 1) {
            var row = jdbc.queryForMap("select workspace_id from evaluation_executions where id = :id", Map.of("id", executionId));
            audit.record((UUID) row.get("workspace_id"), null, "EVALUATION_EXECUTION_COMPLETED",
                    "evaluation_execution", executionId.toString(), null, Map.of("status", status));
        }
    }

    private Target target(UUID workspaceId, UUID suiteId, UUID agentVersionId, UUID knowledgeVersionId) {
        Integer suite = jdbc.queryForObject("""
                select count(*)::int from evaluation_suites where id = :id and workspace_id = :workspace and enabled
                """, Map.of("id", suiteId, "workspace", workspaceId), Integer.class);
        if (suite == null || suite == 0) throw new ApiException("NOT_FOUND", "Enabled evaluation suite not found.", 404);
        var targets = jdbc.query("""
                select av.provider, av.model, av.knowledge_base_id
                from agent_versions av join agents a on a.id = av.agent_id
                where av.id = :version and a.workspace_id = :workspace
                """, Map.of("version", agentVersionId, "workspace", workspaceId), (rs, n) -> new Target(
                rs.getString("provider"), rs.getString("model"), UUID.fromString(rs.getString("knowledge_base_id"))));
        if (targets.isEmpty()) throw new ApiException("NOT_FOUND", "Agent version not found.", 404);
        Integer knowledge = jdbc.queryForObject("""
                select count(*)::int from knowledge_base_versions kv join knowledge_bases kb on kb.id = kv.knowledge_base_id
                where kv.id = :version and kv.knowledge_base_id = :kb and kb.workspace_id = :workspace
                """, Map.of("version", knowledgeVersionId, "kb", targets.get(0).knowledgeBaseId(), "workspace", workspaceId),
                Integer.class);
        if (knowledge == null || knowledge == 0) {
            throw new ApiException("VALIDATION_FAILED", "Knowledge version must belong to the agent version's knowledge base.", 400);
        }
        return targets.get(0);
    }

    private Map<String, Object> comparisonHeader(UUID workspaceId, UUID id) {
        var rows = jdbc.query("""
                select e.id, e.status, e.agent_version_id, e.knowledge_base_version_id, e.evaluator_version,
                       e.created_at, s.suite_key, s.name as suite_name, s.version_number as suite_version,
                       av.version_number as agent_version, kv.version_number as knowledge_version,
                       count(r.id) filter (where r.status in ('PASS','FAIL')) as decisive,
                       count(r.id) filter (where r.status = 'PASS') as passes,
                       count(r.id) filter (where r.status = 'FAIL') as failures
                from evaluation_executions e
                join evaluation_suites s on s.id = e.suite_id
                join agent_versions av on av.id = e.agent_version_id
                join knowledge_base_versions kv on kv.id = e.knowledge_base_version_id
                left join evaluation_results r on r.execution_id = e.id
                where e.id = :id and e.workspace_id = :workspace
                group by e.id, s.suite_key, s.name, s.version_number, av.version_number, kv.version_number
                """, Map.of("id", id, "workspace", workspaceId), (rs, n) -> {
            var row = new LinkedHashMap<String, Object>();
            long decisive = rs.getLong("decisive");
            row.put("id", UUID.fromString(rs.getString("id")));
            row.put("status", rs.getString("status"));
            row.put("suiteKey", rs.getString("suite_key"));
            row.put("suiteName", rs.getString("suite_name"));
            row.put("suiteVersion", rs.getInt("suite_version"));
            row.put("agentVersionId", UUID.fromString(rs.getString("agent_version_id")));
            row.put("agentVersion", rs.getInt("agent_version"));
            row.put("knowledgeVersionId", UUID.fromString(rs.getString("knowledge_base_version_id")));
            row.put("knowledgeVersion", rs.getInt("knowledge_version"));
            row.put("evaluatorVersion", rs.getString("evaluator_version"));
            row.put("passRate", decisive == 0 ? null : rs.getLong("passes") / (double) decisive);
            row.put("failures", rs.getLong("failures"));
            row.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
            return row;
        });
        if (rows.isEmpty()) throw new ApiException("NOT_FOUND", "Evaluation execution not found.", 404);
        return rows.get(0);
    }

    private long regressionCount(UUID workspaceId) {
        Long value = jdbc.queryForObject("""
                with ranked as (
                  select e.id, s.suite_key, row_number() over (
                    partition by s.suite_key order by e.created_at desc
                  ) as position
                  from evaluation_executions e join evaluation_suites s on s.id = e.suite_id
                  where e.workspace_id = :workspace and e.status in ('COMPLETED','PARTIAL')
                ), pairs as (
                  select now_r.id as candidate, old_r.id as baseline
                  from ranked now_r join ranked old_r on old_r.suite_key = now_r.suite_key
                  where now_r.position = 1 and old_r.position = 2
                )
                select count(*) from pairs p
                join evaluation_results now_result on now_result.execution_id = p.candidate
                join evaluation_cases now_case on now_case.id = now_result.case_id
                join evaluation_results old_result on old_result.execution_id = p.baseline
                join evaluation_cases old_case on old_case.id = old_result.case_id and old_case.case_key = now_case.case_key
                where old_result.status = 'PASS' and now_result.status = 'FAIL'
                """, Map.of("workspace", workspaceId), Long.class);
        return value == null ? 0 : value;
    }

    private void insertSignal(UUID workspaceId, UUID runId, UUID resultId, String type, String disposition,
                              String severity, Object evidence) {
        String safeDisposition = Set.of("DETECTED", "BLOCKED", "APPROVED", "REJECTED", "ABSTAINED", "FAILED", "UNKNOWN")
                .contains(disposition) ? disposition : "UNKNOWN";
        String safeSeverity = Set.of("LOW", "MEDIUM", "HIGH").contains(severity) ? severity : "MEDIUM";
        jdbc.update("""
                insert into safety_signals (
                    id, workspace_id, run_id, evaluation_result_id, signal_type, disposition, severity, evidence
                ) values (:id, :workspace, :run, :result, :type, :disposition, :severity, :evidence)
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID()).addValue("workspace", workspaceId)
                .addValue("run", runId).addValue("result", resultId).addValue("type", type)
                .addValue("disposition", safeDisposition).addValue("severity", safeSeverity)
                .addValue("evidence", Jsons.jsonb(Jsons.write(mapper, evidence))));
    }

    private Map<String, Object> executionSummary(java.sql.ResultSet rs) throws java.sql.SQLException {
        var row = new LinkedHashMap<String, Object>();
        row.put("id", UUID.fromString(rs.getString("id")));
        row.put("status", rs.getString("status"));
        row.put("suiteName", rs.getString("suite_name"));
        row.put("suiteVersion", rs.getInt("suite_version"));
        row.put("agentVersion", rs.getInt("agent_version"));
        row.put("knowledgeVersion", rs.getInt("knowledge_version"));
        row.put("provider", rs.getString("provider"));
        row.put("model", rs.getString("model"));
        row.put("evaluatorVersion", rs.getString("evaluator_version"));
        row.put("traceId", rs.getString("trace_id"));
        row.put("createdAt", instant(rs, "created_at"));
        row.put("startedAt", instant(rs, "started_at"));
        row.put("completedAt", instant(rs, "completed_at"));
        row.put("totalCases", rs.getLong("total_cases"));
        row.put("completedCases", rs.getLong("completed_cases"));
        row.put("passes", rs.getLong("passes"));
        row.put("failures", rs.getLong("failures"));
        return row;
    }

    private static Map<String, Object> rate(Map<String, Object> row, String numerator, String denominator, String definition) {
        long n = count(row.get(numerator));
        long d = count(row.get(denominator));
        var result = new LinkedHashMap<String, Object>();
        result.put("value", d == 0 ? null : n / (double) d);
        result.put("status", d == 0 ? "NO_DATA" : "OK");
        result.put("numerator", n);
        result.put("denominator", d);
        result.put("definition", definition);
        return result;
    }

    private static Map<String, Object> page(List<Map<String, Object>> items, Long total, int page) {
        return Map.of("items", items, "total", total == null ? 0 : total, "page", page, "size", PAGE_SIZE);
    }

    private static long count(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String slug(String value) {
        return value.trim().toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.|\\.$", "");
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        return map.get(key) instanceof Boolean value ? value : fallback;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number value ? value.intValue() : fallback;
    }

    static void validateNoPrivateReasoning(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase().replaceAll("[^a-z]", "");
                if (key.contains("chainofthought") || key.contains("scratchpad")
                        || key.contains("hiddenreasoning") || key.contains("privatereasoning")
                        || key.contains("internalmonologue")) {
                    throw new ApiException("VALIDATION_FAILED",
                            "Evaluation expectations cannot request or store private reasoning.", 400);
                }
                validateNoPrivateReasoning(entry.getValue());
            }
        } else if (value instanceof Iterable<?> values) {
            values.forEach(EvaluationService::validateNoPrivateReasoning);
        } else if (value instanceof String text) {
            String normalized = text.toLowerCase();
            if (normalized.contains("chain of thought") || normalized.contains("chain-of-thought")
                    || normalized.contains("private reasoning") || normalized.contains("hidden reasoning")
                    || normalized.contains("internal monologue") || normalized.contains("scratchpad")) {
                throw new ApiException("VALIDATION_FAILED",
                        "Evaluation expectations cannot request or store private reasoning.", 400);
            }
        }
    }

    private static String instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toInstant().toString();
    }

    public record CaseInput(String caseKey, String name, String description, String category, String input,
                            Map<String, Object> expectations, boolean enabled) {}
    public record SuiteInput(String suiteKey, String name, String description, List<UUID> caseIds, boolean enabled) {}
    public record StartInput(UUID suiteId, UUID agentVersionId, UUID knowledgeVersionId, String requestKey) {}
    private record Target(String provider, String model, UUID knowledgeBaseId) {}
    private record Dispatch(UUID id, UUID workspaceId, UUID requestedBy, UUID agentVersionId,
                            UUID knowledgeVersionId, String status) {}
    private record Pending(UUID id, String executionType, String input, Map<String, Object> expectations) {}
    private record ResultRef(String status, UUID executionId, UUID workspaceId, UUID productRunId) {}
    private record Tool(String classification, boolean approvalRequired, String permission) {}
}
