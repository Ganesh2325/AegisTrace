package com.aegistrace.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationDefinitionTest {
    @Test
    void fixtureCatalogHasStableExternallyTestableCoverage() {
        var fixtures = EvaluationFixtures.cases();
        assertEquals(17, fixtures.size());
        assertEquals(fixtures.size(), fixtures.stream().map(EvaluationFixtures.Fixture::key).distinct().count());
        assertTrue(fixtures.stream().allMatch(item -> !item.input().isBlank()));
        assertEquals(
                Set.of("CORRECTNESS", "GROUNDING", "CITATION", "POLICY", "TOOL_BEHAVIOR",
                        "ABSTENTION", "PROMPT_INJECTION"),
                fixtures.stream().map(EvaluationFixtures.Fixture::category).collect(java.util.stream.Collectors.toSet())
        );
    }

    @Test
    void definitionsNeverRequestPrivateReasoning() {
        for (var fixture : EvaluationFixtures.cases()) {
            String serialized = fixture.expectations().toString().toLowerCase();
            assertFalse(serialized.contains("chain-of-thought"), fixture.key());
            assertFalse(serialized.contains("chain_of_thought"), fixture.key());
            assertFalse(serialized.contains("scratchpad"), fixture.key());
            assertFalse(serialized.contains("hidden reasoning"), fixture.key());
        }
    }

    @Test
    void authoringRejectsPrivateReasoningExpectations() {
        assertThrows(RuntimeException.class, () ->
                EvaluationService.validateNoPrivateReasoning(Map.of("expectedChainOfThought", "anything")));
        assertThrows(RuntimeException.class, () ->
                EvaluationService.validateNoPrivateReasoning(Map.of("criteria", "match the hidden reasoning")));
        EvaluationService.validateNoPrivateReasoning(Map.of("expectedAnswerContains", Set.of("refund")));
    }
}
