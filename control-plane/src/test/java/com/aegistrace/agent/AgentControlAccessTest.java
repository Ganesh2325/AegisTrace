package com.aegistrace.agent;

import com.aegistrace.common.ApiException;
import com.aegistrace.security.Actor;
import com.aegistrace.security.Rbac;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentControlAccessTest {
    private final Rbac rbac = new Rbac();
    private final String[] view = {"DEVELOPER", "ADMIN", "OPERATOR", "REVIEWER"};
    private final String[] configure = {"DEVELOPER", "ADMIN"};

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void operatorMayViewAndIsDeniedConfigure() {
        UUID workspace = UUID.randomUUID();
        signIn(workspace, "OPERATOR");
        HttpServletRequest request = new MockHttpServletRequest();
        assertEquals("OPERATOR", rbac.require(request, view).role());
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(request, configure));
        assertEquals(403, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void reviewerMayViewAndIsDeniedConfigure() {
        UUID workspace = UUID.randomUUID();
        signIn(workspace, "REVIEWER");
        HttpServletRequest request = new MockHttpServletRequest();
        assertEquals("REVIEWER", rbac.require(request, view).role());
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(request, configure));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void developerAndAdminMayConfigure() {
        for (String role : configure) {
            UUID workspace = UUID.randomUUID();
            signIn(workspace, role);
            assertEquals(role, rbac.require(new MockHttpServletRequest(), configure).role());
        }
    }

    @Test
    void aForeignWorkspaceHeaderCannotSelectAnotherTenancy() {
        UUID own = UUID.randomUUID();
        signIn(own, "DEVELOPER");
        var request = new MockHttpServletRequest();
        request.addHeader("X-Workspace-Id", UUID.randomUUID().toString());
        ApiException ex = assertThrows(ApiException.class, () -> rbac.require(request, configure));
        assertEquals(403, ex.getStatus());
    }

    private void signIn(UUID workspace, String role) {
        var actor = new Actor(UUID.randomUUID(), role.toLowerCase() + "@example.test", role, "ACTIVE",
                List.of(new Actor.Membership(workspace, role)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }
}
