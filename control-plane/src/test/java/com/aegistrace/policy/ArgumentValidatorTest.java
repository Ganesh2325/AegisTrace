package com.aegistrace.policy;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArgumentValidatorTest {
    @Test
    void unknownTicketFieldIsDenied() {
        var args = new HashMap<String, Object>();
        args.put("subject", "Help");
        args.put("description", "Details");
        args.put("priority", "normal");
        args.put("category", "general");
        args.put("approved", true);
        var result = ArgumentValidator.validate("create_support_ticket", args);
        assertFalse(result.valid());
        assertTrue(result.error().contains("Unknown argument"));
    }

    @Test
    void unknownPriorityIsInvalid() {
        var result = ArgumentValidator.validate("create_support_ticket", Map.of(
                "subject", "Help", "description", "Details", "priority", "p0", "category", "general"));
        assertFalse(result.valid());
    }

    @Test
    void normalTicketIsValid() {
        var result = ArgumentValidator.validate("create_support_ticket", Map.of(
                "subject", "Help", "description", "Details", "priority", "normal", "category", "application_review"));
        assertTrue(result.valid());
        assertEquals(null, result.error());
    }
}
