package com.aegistrace.evaluation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationAccessTest {
    @Test
    void authoringAndExecutionAreDeveloperAdminOnly() {
        assertTrue(EvaluationAccess.canManage("DEVELOPER"));
        assertTrue(EvaluationAccess.canManage("ADMIN"));
        assertFalse(EvaluationAccess.canManage("OPERATOR"));
        assertFalse(EvaluationAccess.canManage("REVIEWER"));
    }

    @Test
    void reviewerCanInspectButOperatorCannotReadEvaluationCases() {
        assertTrue(EvaluationAccess.canRead("REVIEWER"));
        assertTrue(EvaluationAccess.canRead("DEVELOPER"));
        assertFalse(EvaluationAccess.canRead("OPERATOR"));
    }
}
