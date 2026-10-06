package com.aegistrace.run;

import com.aegistrace.common.ApiException;

import java.util.Set;
import java.util.UUID;

public final class RunVisibility {
    private static final Set<String> PROPOSAL_ARGUMENT_ROLES = Set.of("REVIEWER", "ADMIN", "DEVELOPER");

    private RunVisibility() {}

    public static void assertVisible(String role, UUID actorId, UUID ownerId, UUID requestWorkspace, UUID runWorkspace,
                                     boolean hasApproval) {
        if (runWorkspace == null || !runWorkspace.equals(requestWorkspace)) {
            throw new ApiException("NOT_FOUND", "Run not found.", 404);
        }
        if ("OPERATOR".equals(role) && !actorId.equals(ownerId)) {
            throw new ApiException("FORBIDDEN", "Operators can only inspect their own runs.", 403);
        }
        if ("REVIEWER".equals(role) && !hasApproval) {
            throw new ApiException("FORBIDDEN", "Reviewers can inspect runs that have an approval.", 403);
        }
    }

    public static boolean includeProposalArguments(String role) {
        return PROPOSAL_ARGUMENT_ROLES.contains(role);
    }

    public static boolean canCancel(String role, UUID actorId, UUID ownerId, String state) {
        if (!RunStateMachine.canTransition(state, "CANCELLED")) {
            return false;
        }
        if ("ADMIN".equals(role)) {
            return true;
        }
        return "OPERATOR".equals(role) && actorId.equals(ownerId);
    }

    public static boolean canReadApprovals(String role) {
        return "REVIEWER".equals(role) || "ADMIN".equals(role);
    }
}
