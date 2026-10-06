package com.aegistrace.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditRecordsTest {
    @Test
    void nullActorIsSystemNotAi() {
        assertEquals("SYSTEM", AuditRecords.actorKind(null));
        assertEquals("HUMAN", AuditRecords.actorKind(UUID.randomUUID()));
    }

    @Test
    void policyDenyIsDeniedResult() {
        assertEquals("DENIED", AuditRecords.result("POLICY_DECISION", Map.of("decision", "DENY")));
        assertEquals("SUCCESS", AuditRecords.result("APPROVAL_APPROVED", Map.of()));
        assertEquals("FAILED", AuditRecords.result("LOGIN_FAILED", Map.of()));
    }

    @Test
    void sanitizeDropsPromptsAndNestedMaps() {
        var safe = AuditRecords.sanitize(Map.of(
                "decision", "DENY",
                "prompt", "hidden",
                "arguments", Map.of("body", "secret")
        ));
        assertEquals("DENY", safe.get("decision"));
        assertFalse(safe.containsKey("prompt"));
        assertFalse(safe.containsKey("arguments"));
    }

    @Test
    void approvalIdComesFromResourceNotFreeText() {
        assertEquals("ap-1", AuditRecords.approvalId("approval", "ap-1", Map.of()));
        assertEquals("ap-2", AuditRecords.approvalId("tool", "x", Map.of("approvalId", "ap-2")));
    }

    @Test
    void developerAndAdminMayReadAudit() {
        assertTrue(AuditAccess.canRead("DEVELOPER"));
        assertTrue(AuditAccess.canRead("ADMIN"));
        assertFalse(AuditAccess.canRead("OPERATOR"));
        assertFalse(AuditAccess.canRead("REVIEWER"));
    }
}
