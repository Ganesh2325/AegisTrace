package com.aegistrace.security;

import com.aegistrace.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class Rbac {
    public Actor current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Actor actor)) {
            throw new ApiException("UNAUTHENTICATED", "Authentication is required.", HttpStatus.UNAUTHORIZED.value());
        }
        return actor;
    }

    public UUID workspace(HttpServletRequest request) {
        Actor actor = current();
        String header = request.getHeader("X-Workspace-Id");
        if (header != null && !header.isBlank()) {
            UUID id;
            try {
                id = UUID.fromString(header);
            } catch (IllegalArgumentException ex) {
                throw new ApiException("WORKSPACE_REQUIRED", "X-Workspace-Id is not a UUID.", 400);
            }
            if (actor.membership(id) == null) {
                throw new ApiException("FORBIDDEN", "You are not a member of that workspace.", 403);
            }
            return id;
        }
        if (actor.memberships().size() == 1) {
            return actor.memberships().get(0).workspaceId();
        }
        throw new ApiException("WORKSPACE_REQUIRED", "X-Workspace-Id is required.", 400);
    }

    public Actor.Membership require(HttpServletRequest request, String... roles) {
        UUID workspaceId = workspace(request);
        Actor actor = current();
        var membership = actor.membership(workspaceId);
        if (membership == null || !Set.of(roles).contains(membership.role())) {
            throw new ApiException("FORBIDDEN", "Your role cannot perform this action.", 403);
        }
        return membership;
    }
}
