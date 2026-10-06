package com.aegistrace.agent;

import com.aegistrace.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAccessTest {
    @Test
    void everyMemberRoleCanViewAgents() {
        for (String role : new String[]{"OPERATOR", "REVIEWER", "DEVELOPER", "ADMIN"}) {
            assertTrue(AgentAccess.canView(role));
        }
    }

    @Test
    void onlyDeveloperAndAdminCanConfigure() {
        assertFalse(AgentAccess.canConfigure("OPERATOR"));
        assertFalse(AgentAccess.canConfigure("REVIEWER"));
        assertTrue(AgentAccess.canConfigure("DEVELOPER"));
        assertTrue(AgentAccess.canConfigure("ADMIN"));
    }

    @Test
    void foreignWorkspaceLooksLikeAMissingAgent() {
        UUID own = UUID.randomUUID();
        ApiException ex = assertThrows(ApiException.class, () -> AgentAccess.assertWorkspace(own, UUID.randomUUID()));
        assertEquals(404, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getCode());
    }
}
