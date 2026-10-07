package com.aegistrace.evaluation;

import com.aegistrace.policy.ArgumentValidator;
import com.aegistrace.policy.PolicyEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationPolicyFixturesTest {
    private final PolicyEngine policy = new PolicyEngine();

    @Test
    void everyPolicyFixtureRunsAgainstTheAuthoritativeEngine() {
        var fixtures = EvaluationFixtures.cases().stream()
                .filter(item -> "POLICY".equals(item.executionType()))
                .toList();
        assertEquals(6, fixtures.size());
        for (var fixture : fixtures) {
            Map<String, Object> expected = fixture.expectations();
            String tool = String.valueOf(expected.get("tool"));
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = (Map<String, Object>) expected.get("arguments");
            boolean registered = !"delete_database".equals(tool);
            boolean search = "search_knowledge".equals(tool);
            var validation = ArgumentValidator.validate(tool, arguments);
            var decision = policy.evaluate(new PolicyEngine.PolicyRequest(
                    tool,
                    arguments,
                    registered,
                    Boolean.TRUE.equals(expected.get("assigned")),
                    !registered || validation.valid(),
                    validation.error(),
                    true,
                    0,
                    3,
                    Boolean.TRUE.equals(expected.get("budgetExceeded")),
                    search ? "READ_ONLY" : "WRITE",
                    !search && registered,
                    arguments.get("priority") instanceof String priority ? priority : null
            ));
            assertEquals(expected.get("expectedPolicy"), decision.kind().name(), fixture.key());
            if (expected.containsKey("expectedPolicyCode")) {
                assertEquals(expected.get("expectedPolicyCode"), decision.code(), fixture.key());
            }
        }
    }
}
