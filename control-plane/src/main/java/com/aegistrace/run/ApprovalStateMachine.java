package com.aegistrace.run;

import java.util.Set;

public final class ApprovalStateMachine {
    public static final Set<String> TERMINAL = Set.of("APPROVED", "REJECTED", "EXPIRED", "CANCELLED");

    private ApprovalStateMachine() {}

    public static boolean canTransition(String from, String to) {
        return "PENDING".equals(from) && TERMINAL.contains(to);
    }

    public static boolean isTerminal(String status) {
        return TERMINAL.contains(status);
    }

    public static boolean isIdempotentReplay(String current, boolean approve) {
        return approve && "APPROVED".equals(current) || !approve && "REJECTED".equals(current);
    }

    public static String conflictCode(String current) {
        return switch (current) {
            case "APPROVED" -> "APPROVAL_ALREADY_APPROVED";
            case "REJECTED" -> "APPROVAL_ALREADY_REJECTED";
            case "EXPIRED" -> "APPROVAL_EXPIRED";
            case "CANCELLED" -> "APPROVAL_CANCELLED";
            default -> "CONFLICT";
        };
    }

    public static String conflictMessage(String current, boolean approve) {
        String verb = approve ? "approved" : "rejected";
        return switch (current) {
            case "APPROVED" -> "This approval is already approved.";
            case "REJECTED" -> "This approval is already rejected.";
            case "EXPIRED" -> "The approval has expired.";
            case "CANCELLED" -> "This approval was cancelled.";
            default -> "The approval can no longer be " + verb + ".";
        };
    }
}
