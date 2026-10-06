package com.aegistrace.audit;

public final class AuditAccess {
    public static final String[] READ_ROLES = {"DEVELOPER", "ADMIN"};

    private AuditAccess() {}

    public static boolean canRead(String role) {
        return "DEVELOPER".equals(role) || "ADMIN".equals(role);
    }
}
