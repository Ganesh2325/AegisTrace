package com.aegistrace.evaluation;

import java.util.Set;

public final class EvaluationAccess {
    public static final String[] READ_ROLES = {"DEVELOPER", "REVIEWER", "ADMIN"};
    public static final String[] MANAGE_ROLES = {"DEVELOPER", "ADMIN"};
    public static final String[] SAFETY_ROLES = {"OPERATOR", "DEVELOPER", "REVIEWER", "ADMIN"};

    private static final Set<String> READ = Set.of(READ_ROLES);
    private static final Set<String> MANAGE = Set.of(MANAGE_ROLES);

    private EvaluationAccess() {}

    public static boolean canRead(String role) {
        return READ.contains(role);
    }

    public static boolean canManage(String role) {
        return MANAGE.contains(role);
    }
}
