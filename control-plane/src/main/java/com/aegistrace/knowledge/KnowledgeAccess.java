package com.aegistrace.knowledge;

import com.aegistrace.common.ApiException;

import java.util.Set;
import java.util.UUID;

public final class KnowledgeAccess {
    private static final Set<String> READ = Set.of("OPERATOR", "DEVELOPER", "ADMIN");
    private static final Set<String> MANAGE = Set.of("DEVELOPER", "ADMIN");

    private KnowledgeAccess() {}

    public static boolean canRead(String role) {
        return READ.contains(role);
    }

    public static boolean canManage(String role) {
        return MANAGE.contains(role);
    }

    public static void assertWorkspace(UUID requestWorkspace, UUID resourceWorkspace) {
        if (resourceWorkspace == null || !resourceWorkspace.equals(requestWorkspace)) {
            throw new ApiException("NOT_FOUND", "Knowledge resource not found.", 404);
        }
    }
}
