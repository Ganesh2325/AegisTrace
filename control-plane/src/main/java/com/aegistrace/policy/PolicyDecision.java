package com.aegistrace.policy;

public record PolicyDecision(Kind kind, String requiredRole, String code, String reason) {
    public enum Kind {
        ALLOW, DENY, REQUIRE_APPROVAL, REQUIRE_ADMIN_APPROVAL
    }

    public static PolicyDecision deny(String code, String reason) {
        return new PolicyDecision(Kind.DENY, null, code, reason);
    }

    public boolean denied() {
        return kind == Kind.DENY;
    }
}
