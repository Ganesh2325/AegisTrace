package com.aegistrace.observability;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductPhasesTest {
    @Test
    void pairsRetrievalAndApprovalWait() {
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        var phases = ProductPhases.from(List.of(
                new ProductPhases.Event("RETRIEVAL_STARTED", t0, Map.of(), "RETRIEVING"),
                new ProductPhases.Event("RETRIEVAL_COMPLETED", t0.plusMillis(40), Map.of("chunkCount", 3), "RETRIEVING"),
                new ProductPhases.Event("APPROVAL_REQUIRED", t0.plusMillis(100), Map.of(), "APPROVAL_REQUIRED"),
                new ProductPhases.Event("APPROVAL_APPROVED", t0.plusMillis(700), Map.of(), "TOOL_EXECUTING")
        ));
        assertEquals(40L, phases.get(0).durationMs());
        assertEquals("retrieval", phases.get(0).name());
        assertEquals("PRODUCT_EVENT", phases.get(0).kind());
        assertEquals(600L, phases.get(1).durationMs());
        assertEquals("approval.wait", phases.get(1).name());
    }

    @Test
    void doesNotInventMissingCloses() {
        Instant t0 = Instant.parse("2026-10-06T12:00:00Z");
        var phases = ProductPhases.from(List.of(
                new ProductPhases.Event("MODEL_STARTED", t0, Map.of(), "THINKING")
        ));
        assertTrue(phases.isEmpty());
    }
}
