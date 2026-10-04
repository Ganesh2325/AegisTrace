package com.aegistrace.security;

import java.util.List;
import java.util.UUID;

public record Actor(UUID id, String email, String displayName, String status, List<Membership> memberships) {
    public record Membership(UUID workspaceId, String role) {}

    public Membership membership(UUID workspaceId) {
        return memberships.stream().filter(m -> m.workspaceId().equals(workspaceId)).findFirst().orElse(null);
    }
}
