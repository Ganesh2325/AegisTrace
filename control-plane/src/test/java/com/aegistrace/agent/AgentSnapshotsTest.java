package com.aegistrace.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentSnapshotsTest {
    @Test
    void withoutPromptRemovesSystemPromptAndKeepsVersion() {
        var snapshot = Map.<String, Object>of(
                "version", 1,
                "model", "grounded-extractive-v1",
                "systemPrompt", "hidden instructions");
        var publicView = AgentSnapshots.withoutPrompt(snapshot);
        assertFalse(publicView.containsKey("systemPrompt"));
        assertEquals(1, publicView.get("version"));
        assertEquals("grounded-extractive-v1", publicView.get("model"));
        assertEquals(1, AgentSnapshots.versionMarker(snapshot));
    }

    @Test
    void withoutPromptAcceptsNull() {
        assertEquals(0, AgentSnapshots.withoutPrompt(null).size());
        assertNull(AgentSnapshots.versionMarker(null));
    }
}
