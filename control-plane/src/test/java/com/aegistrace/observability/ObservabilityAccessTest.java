package com.aegistrace.observability;

import com.aegistrace.audit.AuditAccess;
import com.aegistrace.common.ApiException;
import com.aegistrace.metrics.MetricsService;
import com.aegistrace.security.Actor;
import com.aegistrace.security.Rbac;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservabilityAccessTest {
    private final Rbac rbac = new Rbac();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void memberRolesMayReadObservability() {
        for (String role : MetricsService.READ_ROLES) {
            UUID workspace = UUID.randomUUID();
            signIn(workspace, role);
            assertEquals(role, rbac.require(new MockHttpServletRequest(), ObservabilityAccess.READ_ROLES).role());
        }
    }

    @Test
    void operatorCannotReadAudit() {
        UUID workspace = UUID.randomUUID();
        signIn(workspace, "OPERATOR");
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(new MockHttpServletRequest(), AuditAccess.READ_ROLES));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void foreignWorkspaceHeaderCannotSelectAnotherTenancy() {
        signIn(UUID.randomUUID(), "DEVELOPER");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", UUID.randomUUID().toString());
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(request, ObservabilityAccess.READ_ROLES));
        assertEquals(403, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void reviewerCannotReadAudit() {
        signIn(UUID.randomUUID(), "REVIEWER");
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(new MockHttpServletRequest(), AuditAccess.READ_ROLES));
        assertEquals(403, ex.getStatus());
    }

    private void signIn(UUID workspace, String role) {
        var actor = new Actor(UUID.randomUUID(), role.toLowerCase() + "@example.test", role, "ACTIVE",
                List.of(new Actor.Membership(workspace, role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }
}
