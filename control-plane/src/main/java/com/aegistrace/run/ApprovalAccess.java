package com.aegistrace.run;

import java.util.Set;
import java.util.UUID;

/**
 * Reviewer and Admin may read and decide approvals. Operators never decide.
 * Reviewers cannot approve a request they originated. Admins may, because
 * they are the workspace override role already used for admin-only tools.
 */
public final class ApprovalAccess {
    private static final Set<String> READ = Set.of("REVIEWER", "ADMIN");
    private static final Set<String> DECIDE = Set.of("REVIEWER", "ADMIN");
    private static final Set<String> SELF_APPROVE = Set.of("ADMIN");

    private ApprovalAccess() {}

    public static boolean canRead(String role) {
        return READ.contains(role);
    }

    public static boolean canDecide(String role) {
        return DECIDE.contains(role);
    }

    public static boolean allowsSelfApproval(String role) {
        return SELF_APPROVE.contains(role);
    }

    public static boolean canDecideThis(String role, UUID actorId, UUID requesterId) {
        if (!canDecide(role)) {
            return false;
        }
        if (actorId != null && actorId.equals(requesterId) && !allowsSelfApproval(role)) {
            return false;
        }
        return true;
    }
}
