package com.aegistrace.metrics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationsAttentionTest {
    @Test
    void highLatencyPendingTimeoutAndFailureProduceDeterministicItems() {
        var summary = MetricsCalculator.summarize(List.of(
                run("COMPLETED", 400_000d),
                run("FAILED", 1000d),
                run("TIMED_OUT", 2000d)
        ), List.of(new MetricsCalculator.ApprovalSample("PENDING", null)), 5, 0, 0, 0, 0);
        var active = List.of(Map.<String, Object>of("id", UUID.randomUUID(), "state", "APPROVAL_REQUIRED"));
        var approvals = List.of(Map.<String, Object>of("status", "PENDING"));
        var items = OperationsService.attention(summary, active, approvals, 5);
        assertEquals("HIGH_LATENCY", items.get(0).get("code"));
        assertEquals("PENDING_APPROVAL", items.get(1).get("code"));
        assertEquals("TIMED_OUT", items.get(2).get("code"));
        assertEquals("FAILED", items.get(3).get("code"));
        assertEquals("QUEUE", items.get(4).get("code"));
        assertEquals("ACTIVE_APPROVAL", items.get(5).get("code"));
        assertTrue(items.stream().noneMatch(item -> "CRITICAL".equals(item.get("severity"))));
    }

    @Test
    void emptyWorkspaceHasNoAttention() {
        var summary = MetricsCalculator.summarize(List.of(), List.of(), 0, 0, 0, 0, 0);
        assertTrue(OperationsService.attention(summary, List.of(), List.of(), 0).isEmpty());
    }

    private static MetricsCalculator.RunSample run(String state, Double durationMs) {
        return new MetricsCalculator.RunSample(state, durationMs, 0, 0, BigDecimal.ZERO, "grounded-extractive-v1");
    }
}
