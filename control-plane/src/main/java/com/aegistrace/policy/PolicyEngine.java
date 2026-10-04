package com.aegistrace.policy;

import java.util.Map;
import java.util.Set;

/**
 * Deterministic authorization for tool calls.
 * Validity and budgets are checked before a human is asked to approve a write
 * that the system would refuse to run.
 */
public final class PolicyEngine {
    public static final Set<String> DENIED_PRIORITIES = Set.of("urgent", "critical");
    public static final Set<String> ADMIN_PRIORITIES = Set.of("high");

    public PolicyDecision evaluate(PolicyRequest request) {
        if (request.toolName() == null || request.toolName().isBlank() || !request.toolRegistered()) {
            return PolicyDecision.deny("UNKNOWN_TOOL", "The tool is not registered.");
        }
        if (!request.argumentsValid()) {
            return PolicyDecision.deny("INVALID_ARGUMENTS",
                    request.argumentError() == null ? "Arguments are invalid." : request.argumentError());
        }
        if (!request.assignedToAgent()) {
            return PolicyDecision.deny("TOOL_NOT_ASSIGNED", "The tool is not assigned to this agent version.");
        }
        if (!request.userHasPermission()) {
            return PolicyDecision.deny("FORBIDDEN", "The user lacks permission for this tool.");
        }
        if (request.priorToolCalls() >= request.maxToolCalls()) {
            return PolicyDecision.deny("TOOL_CALL_LIMIT", "The tool-call budget has been exceeded.");
        }
        if (request.budgetExceeded()) {
            return PolicyDecision.deny("BUDGET_EXCEEDED", "The run budget has been exceeded.");
        }
        if (request.priority() != null && DENIED_PRIORITIES.contains(request.priority())) {
            return PolicyDecision.deny("PROHIBITED_PRIORITY", "That ticket priority is not allowed.");
        }
        if (request.priority() != null && ADMIN_PRIORITIES.contains(request.priority())) {
            return new PolicyDecision(PolicyDecision.Kind.REQUIRE_ADMIN_APPROVAL, "ADMIN", "HIGH_PRIORITY",
                    "High priority requires an admin approval.");
        }
        if ("WRITE".equals(request.classification()) || request.approvalRequired()) {
            return new PolicyDecision(PolicyDecision.Kind.REQUIRE_APPROVAL, "REVIEWER", "WRITE_ACTION",
                    "Write tools require human approval.");
        }
        return new PolicyDecision(PolicyDecision.Kind.ALLOW, null, "READ_ONLY", "Read-only tool is allowed.");
    }

    public static boolean permissionAllows(String role, String permission) {
        return switch (permission) {
            case "KNOWLEDGE_READ" -> Set.of("OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN").contains(role);
            case "TICKET_CREATE" -> Set.of("OPERATOR", "ADMIN").contains(role);
            default -> false;
        };
    }

    public record PolicyRequest(
            String toolName,
            Map<String, Object> arguments,
            boolean toolRegistered,
            boolean assignedToAgent,
            boolean argumentsValid,
            String argumentError,
            boolean userHasPermission,
            int priorToolCalls,
            int maxToolCalls,
            boolean budgetExceeded,
            String classification,
            boolean approvalRequired,
            String priority
    ) {}
}
