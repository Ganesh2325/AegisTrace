package com.aegistrace.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalStateMachineTest {
    @Test
    void pendingMayReachEachTerminalState() {
        assertTrue(ApprovalStateMachine.canTransition("PENDING", "APPROVED"));
        assertTrue(ApprovalStateMachine.canTransition("PENDING", "REJECTED"));
        assertTrue(ApprovalStateMachine.canTransition("PENDING", "EXPIRED"));
        assertTrue(ApprovalStateMachine.canTransition("PENDING", "CANCELLED"));
    }

    @Test
    void terminalStatesDoNotTransitionAgain() {
        for (String status : ApprovalStateMachine.TERMINAL) {
            assertTrue(ApprovalStateMachine.isTerminal(status));
            assertFalse(ApprovalStateMachine.canTransition(status, "APPROVED"));
            assertFalse(ApprovalStateMachine.canTransition(status, "REJECTED"));
            assertFalse(ApprovalStateMachine.canTransition(status, "EXPIRED"));
            assertFalse(ApprovalStateMachine.canTransition(status, "CANCELLED"));
            assertFalse(ApprovalStateMachine.canTransition(status, "PENDING"));
        }
    }

    @Test
    void sameDirectionReplayIsIdempotent() {
        assertTrue(ApprovalStateMachine.isIdempotentReplay("APPROVED", true));
        assertTrue(ApprovalStateMachine.isIdempotentReplay("REJECTED", false));
        assertFalse(ApprovalStateMachine.isIdempotentReplay("REJECTED", true));
        assertFalse(ApprovalStateMachine.isIdempotentReplay("APPROVED", false));
        assertFalse(ApprovalStateMachine.isIdempotentReplay("EXPIRED", true));
        assertFalse(ApprovalStateMachine.isIdempotentReplay("CANCELLED", true));
        assertFalse(ApprovalStateMachine.isIdempotentReplay("PENDING", true));
    }

    @Test
    void conflictCodesDistinguishTerminalStates() {
        assertEquals("APPROVAL_ALREADY_REJECTED", ApprovalStateMachine.conflictCode("REJECTED"));
        assertEquals("APPROVAL_ALREADY_APPROVED", ApprovalStateMachine.conflictCode("APPROVED"));
        assertEquals("APPROVAL_EXPIRED", ApprovalStateMachine.conflictCode("EXPIRED"));
        assertEquals("APPROVAL_CANCELLED", ApprovalStateMachine.conflictCode("CANCELLED"));
        assertEquals("This approval is already rejected.", ApprovalStateMachine.conflictMessage("REJECTED", true));
        assertEquals("The approval has expired.", ApprovalStateMachine.conflictMessage("EXPIRED", true));
    }
}
