package com.aegistrace.run;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunStateMachineTest {
    @Test
    void approvalPauseThenResume() {
        assertTrue(RunStateMachine.canTransition("TOOL_PROPOSED", "APPROVAL_REQUIRED"));
        assertTrue(RunStateMachine.canTransition("APPROVAL_REQUIRED", "APPROVED"));
        assertTrue(RunStateMachine.canTransition("APPROVED", "TOOL_EXECUTING"));
        assertTrue(RunStateMachine.canTransition("TOOL_EXECUTING", "COMPLETED"));
    }

    @Test
    void rejectionDoesNotExecuteTheTool() {
        assertTrue(RunStateMachine.canTransition("APPROVAL_REQUIRED", "REJECTED"));
        assertTrue(RunStateMachine.canTransition("REJECTED", "COMPLETED"));
        assertFalse(RunStateMachine.canTransition("REJECTED", "TOOL_EXECUTING"));
    }

    @Test
    void terminalStatesStick() {
        assertTrue(RunStateMachine.isTerminal("COMPLETED"));
        assertTrue(RunStateMachine.isTerminal("FAILED"));
        assertTrue(RunStateMachine.isTerminal("CANCELLED"));
        assertTrue(RunStateMachine.isTerminal("TIMED_OUT"));
        assertFalse(RunStateMachine.canTransition("COMPLETED", "RUNNING"));
        assertFalse(RunStateMachine.canTransition("TIMED_OUT", "TOOL_EXECUTING"));
    }

    @Test
    void waitingIsOnlyTheApprovalPause() {
        assertTrue(RunStateMachine.isWaiting("APPROVAL_REQUIRED"));
        assertFalse(RunStateMachine.isWaiting("RUNNING"));
        assertTrue(RunStateMachine.isActive("RETRIEVING"));
        assertTrue(RunStateMachine.isActive("TOOL_EXECUTING"));
        assertFalse(RunStateMachine.isActive("APPROVAL_REQUIRED"));
        assertFalse(RunStateMachine.isActive("COMPLETED"));
    }
}
