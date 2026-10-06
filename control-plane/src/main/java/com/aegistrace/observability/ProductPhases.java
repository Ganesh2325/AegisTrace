package com.aegistrace.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives product-level timing from persisted run events. These are not OpenTelemetry spans.
 */
public final class ProductPhases {
    public record Event(String type, Instant at, Map<String, Object> payload, String state) {}

    public record Phase(String name, String kind, Instant start, Instant end, Long durationMs, String status) {
        Map<String, Object> toMap() {
            var row = new LinkedHashMap<String, Object>();
            row.put("name", name);
            row.put("kind", kind);
            row.put("start", start == null ? null : start.toString());
            row.put("end", end == null ? null : end.toString());
            row.put("durationMs", durationMs);
            row.put("status", status);
            return row;
        }
    }

    private ProductPhases() {}

    public static List<Phase> from(List<Event> events) {
        var phases = new ArrayList<Phase>();
        Instant retrievalStart = null;
        Instant modelStart = null;
        Instant toolStart = null;
        Instant approvalStart = null;
        for (Event event : events) {
            switch (event.type()) {
                case "RETRIEVAL_STARTED" -> retrievalStart = event.at();
                case "RETRIEVAL_COMPLETED" -> phases.add(close("retrieval", "PRODUCT_EVENT", retrievalStart, event, "OK"));
                case "MODEL_STARTED" -> modelStart = event.at();
                case "MODEL_COMPLETED" -> phases.add(close("model", "PRODUCT_EVENT", modelStart, event,
                        event.payload() != null && Boolean.TRUE.equals(event.payload().get("retry")) ? "ERROR" : "OK"));
                case "APPROVAL_REQUIRED" -> approvalStart = event.at();
                case "APPROVAL_APPROVED", "APPROVAL_REJECTED", "APPROVAL_EXPIRED", "APPROVAL_CANCELLED" ->
                        phases.add(close("approval.wait", "PRODUCT_EVENT", approvalStart, event,
                                event.type().endsWith("APPROVED") ? "OK" : "ERROR"));
                case "TOOL_STARTED" -> toolStart = event.at();
                case "TOOL_COMPLETED" -> phases.add(close("tool", "PRODUCT_EVENT", toolStart, event,
                        "SUCCEEDED".equals(String.valueOf(event.payload() == null ? "" : event.payload().get("status")))
                                ? "OK" : "ERROR"));
                default -> {
                }
            }
        }
        return phases.stream().filter(phase -> phase.durationMs() != null).toList();
    }

    public static Map<String, Long> sums(List<Phase> phases) {
        var sums = new LinkedHashMap<String, Long>();
        for (Phase phase : phases) {
            if (phase.durationMs() == null) {
                continue;
            }
            sums.merge(phase.name(), phase.durationMs(), Long::sum);
        }
        return sums;
    }

    private static Phase close(String name, String kind, Instant start, Event event, String status) {
        Instant end = event.at();
        Long duration = start == null || end == null ? null : Math.max(0, Duration.between(start, end).toMillis());
        return new Phase(name, kind, start, end, duration, status);
    }
}
