package com.aegistrace.run;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalAccessTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void onlyReviewerAndAdminCanReadAndDecide() {
        assertTrue(ApprovalAccess.canRead("REVIEWER"));
        assertTrue(ApprovalAccess.canRead("ADMIN"));
        assertTrue(ApprovalAccess.canDecide("REVIEWER"));
        assertTrue(ApprovalAccess.canDecide("ADMIN"));
        assertFalse(ApprovalAccess.canRead("OPERATOR"));
        assertFalse(ApprovalAccess.canDecide("OPERATOR"));
        assertFalse(ApprovalAccess.canRead("DEVELOPER"));
        assertFalse(ApprovalAccess.canDecide("DEVELOPER"));
    }

    @Test
    void reviewerCannotDecideOwnRequest() {
        assertFalse(ApprovalAccess.canDecideThis("REVIEWER", actor, actor));
        assertTrue(ApprovalAccess.canDecideThis("REVIEWER", actor, other));
    }

    @Test
    void adminMayDecideOwnRequest() {
        assertTrue(ApprovalAccess.allowsSelfApproval("ADMIN"));
        assertTrue(ApprovalAccess.canDecideThis("ADMIN", actor, actor));
        assertFalse(ApprovalAccess.allowsSelfApproval("REVIEWER"));
    }

    @Test
    void operatorNeverDecidesEvenIfRequesterDiffers() {
        assertFalse(ApprovalAccess.canDecideThis("OPERATOR", actor, other));
    }
}
