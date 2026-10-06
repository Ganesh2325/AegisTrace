package com.aegistrace.agent;

import com.aegistrace.common.ApiException;

import java.util.Set;
import java.util.UUID;

public final class AgentAccess {
    private static final Set<String> VIEW = Set.of("OPERATOR", "REVIEWER", "DEVELOPER", "ADMIN");
    private static final Set<String> CONFIGURE = Set.of("DEVELOPER", "ADMIN");

    private AgentAccess() {}

    public static boolean canView(String role) {
        return VIEW.contains(role);
    }

    public static boolean canConfigure(String role) {
        return CONFIGURE.contains(role);
    }

    public static void assertWorkspace(UUID requestWorkspace, UUID agentWorkspace) {
        if (agentWorkspace == null || !agentWorkspace.equals(requestWorkspace)) {
            throw new ApiException("NOT_FOUND", "Agent not found.", 404);
        }
    }
}
