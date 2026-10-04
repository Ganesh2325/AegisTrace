package com.aegistrace.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolicyEngineTest {
    private final PolicyEngine engine = new PolicyEngine();

    @Test
    void writeTicketRequiresApproval() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, true, 1, false, "WRITE", true, "normal"));
        assertEquals(PolicyDecision.Kind.REQUIRE_APPROVAL, decision.kind());
        assertEquals("REVIEWER", decision.requiredRole());
    }

    @Test
    void unknownToolIsDenied() {
        var decision = engine.evaluate(request("delete_database", false, false, true, true, 0, false, "WRITE", false, null));
        assertEquals(PolicyDecision.Kind.DENY, decision.kind());
        assertEquals("UNKNOWN_TOOL", decision.code());
    }

    @Test
    void urgentPriorityIsDenied() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, true, 1, false, "WRITE", true, "urgent"));
        assertEquals("PROHIBITED_PRIORITY", decision.code());
    }

    @Test
    void highPriorityRequiresAdmin() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, true, 1, false, "WRITE", true, "high"));
        assertEquals(PolicyDecision.Kind.REQUIRE_ADMIN_APPROVAL, decision.kind());
    }

    @Test
    void toolCallBudgetIsDeniedBeforeApproval() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, true, 3, false, "WRITE", true, "normal"));
        assertEquals("TOOL_CALL_LIMIT", decision.code());
    }

    @Test
    void budgetStopsTheRun() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, true, 1, true, "WRITE", true, "normal"));
        assertEquals("BUDGET_EXCEEDED", decision.code());
    }

    @Test
    void searchIsAllowed() {
        var decision = engine.evaluate(request("search_knowledge", true, true, true, true, 0, false, "READ_ONLY", false, null));
        assertEquals(PolicyDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void missingPermissionIsDenied() {
        var decision = engine.evaluate(request("create_support_ticket", true, true, true, false, 1, false, "WRITE", true, "normal"));
        assertEquals("FORBIDDEN", decision.code());
    }

    private PolicyEngine.PolicyRequest request(String tool, boolean registered, boolean assigned, boolean valid, boolean permitted,
                                               int prior, boolean budget, String classification, boolean approval, String priority) {
        return new PolicyEngine.PolicyRequest(tool, Map.of(), registered, assigned, valid, valid ? null : "bad", permitted,
                prior, 3, budget, classification, approval, priority);
    }
}
