package com.aegistrace.run;

import java.util.Map;
import java.util.Set;

public final class RunStateMachine {
    private static final Map<String, Set<String>> NEXT = Map.ofEntries(
            Map.entry("QUEUED", Set.of("RUNNING", "CANCELLED", "FAILED", "TIMED_OUT")),
            Map.entry("RUNNING", Set.of("RETRIEVING", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")),
            Map.entry("RETRIEVING", Set.of("THINKING", "FAILED", "CANCELLED", "TIMED_OUT")),
            Map.entry("THINKING", Set.of("TOOL_PROPOSED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")),
            Map.entry("TOOL_PROPOSED", Set.of("APPROVAL_REQUIRED", "COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")),
            Map.entry("APPROVAL_REQUIRED", Set.of("APPROVED", "REJECTED", "CANCELLED", "TIMED_OUT", "FAILED")),
            Map.entry("APPROVED", Set.of("TOOL_EXECUTING", "CANCELLED", "TIMED_OUT", "FAILED")),
            Map.entry("REJECTED", Set.of("COMPLETED", "FAILED")),
            Map.entry("TOOL_EXECUTING", Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT")),
            Map.entry("COMPLETED", Set.of()),
            Map.entry("FAILED", Set.of()),
            Map.entry("CANCELLED", Set.of()),
            Map.entry("TIMED_OUT", Set.of())
    );

    private RunStateMachine() {}

    public static boolean canTransition(String from, String to) {
        return NEXT.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isTerminal(String state) {
        return Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT").contains(state);
    }
}
