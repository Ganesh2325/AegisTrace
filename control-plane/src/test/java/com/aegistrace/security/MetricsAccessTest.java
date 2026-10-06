package com.aegistrace.security;

import com.aegistrace.common.ApiException;
import com.aegistrace.metrics.MetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetricsAccessTest {
    private final Rbac rbac = new Rbac();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void eachMemberRoleCanRequestWorkspaceMetrics() {
        UUID workspace = UUID.randomUUID();
        for (String role : MetricsService.READ_ROLES) {
            signIn(workspace, role);
            var membership = rbac.require(new MockHttpServletRequest(), MetricsService.READ_ROLES);
            assertEquals(workspace, membership.workspaceId());
            assertEquals(role, membership.role());
        }
    }

    @Test
    void aForeignWorkspaceHeaderCannotSelectAnotherTenancy() {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        signIn(own, "OPERATOR");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", other.toString());
        ApiException ex = assertThrows(ApiException.class, () -> rbac.workspace(request));
        assertEquals(403, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void metricsUseTheMembershipWorkspaceWhenTheCallerHasOne() {
        UUID workspace = UUID.randomUUID();
        signIn(workspace, "REVIEWER");
        assertEquals(workspace, rbac.workspace(new MockHttpServletRequest()));
    }

    private void signIn(UUID workspace, String role) {
        var actor = new Actor(UUID.randomUUID(), role.toLowerCase() + "@example.test", role, "ACTIVE",
                List.of(new Actor.Membership(workspace, role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }
}
