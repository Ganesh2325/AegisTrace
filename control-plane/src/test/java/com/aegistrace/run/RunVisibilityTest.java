package com.aegistrace.run;

import com.aegistrace.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunVisibilityTest {
    private final UUID workspace = UUID.randomUUID();
    private final UUID otherWorkspace = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();
    private final UUID otherUser = UUID.randomUUID();

    @Test
    void operatorCannotReadAnotherOperatorsRun() {
        ApiException ex = assertThrows(ApiException.class, () ->
                RunVisibility.assertVisible("OPERATOR", otherUser, owner, workspace, workspace, false));
        assertEquals(403, ex.getStatus());
        assertEquals("FORBIDDEN", ex.getCode());
    }

    @Test
    void operatorCanReadOwnRun() {
        RunVisibility.assertVisible("OPERATOR", owner, owner, workspace, workspace, false);
    }

    @Test
    void reviewerCannotReadARunWithoutAnApproval() {
        ApiException ex = assertThrows(ApiException.class, () ->
                RunVisibility.assertVisible("REVIEWER", otherUser, owner, workspace, workspace, false));
        assertEquals(403, ex.getStatus());
    }

    @Test
    void reviewerCanReadARunWithAnApproval() {
        RunVisibility.assertVisible("REVIEWER", otherUser, owner, workspace, workspace, true);
    }

    @Test
    void aForeignWorkspaceRunIsNotFound() {
        ApiException ex = assertThrows(ApiException.class, () ->
                RunVisibility.assertVisible("ADMIN", owner, owner, workspace, otherWorkspace, true));
        assertEquals(404, ex.getStatus());
        assertEquals("NOT_FOUND", ex.getCode());
    }

    @Test
    void developerAndAdminCanReadAnyWorkspaceRun() {
        RunVisibility.assertVisible("DEVELOPER", otherUser, owner, workspace, workspace, false);
        RunVisibility.assertVisible("ADMIN", otherUser, owner, workspace, workspace, false);
    }

    @Test
    void operatorDoesNotReceiveToolArguments() {
        assertFalse(RunVisibility.includeProposalArguments("OPERATOR"));
        assertTrue(RunVisibility.includeProposalArguments("REVIEWER"));
        assertTrue(RunVisibility.includeProposalArguments("ADMIN"));
        assertTrue(RunVisibility.includeProposalArguments("DEVELOPER"));
    }

    @Test
    void cancelIsOnlyOfferedWhileTheStateMachineAllowsIt() {
        assertTrue(RunVisibility.canCancel("OPERATOR", owner, owner, "RUNNING"));
        assertTrue(RunVisibility.canCancel("OPERATOR", owner, owner, "APPROVAL_REQUIRED"));
        assertFalse(RunVisibility.canCancel("OPERATOR", owner, owner, "COMPLETED"));
        assertFalse(RunVisibility.canCancel("OPERATOR", otherUser, owner, "RUNNING"));
        assertFalse(RunVisibility.canCancel("REVIEWER", owner, owner, "RUNNING"));
        assertTrue(RunVisibility.canCancel("ADMIN", otherUser, owner, "TOOL_EXECUTING"));
        assertFalse(RunVisibility.canCancel("ADMIN", otherUser, owner, "REJECTED"));
    }
}
