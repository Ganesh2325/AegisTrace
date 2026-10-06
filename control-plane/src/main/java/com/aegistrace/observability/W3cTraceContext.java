package com.aegistrace.observability;

/**
 * Formats a W3C {@code traceparent}. Invalid or all-zero ids return null
 * rather than a fabricated context.
 */
public final class W3cTraceContext {
    private W3cTraceContext() {}

    public static String traceparent(String traceId, String spanId, boolean sampled) {
        String trace = hex(traceId, 32);
        String span = hex(spanId, 16);
        if (trace == null || span == null) {
            return null;
        }
        return "00-" + trace + "-" + span + "-" + (sampled ? "01" : "00");
    }

    public static String spanId(String raw) {
        return hex(raw, 16);
    }

    static String hex(String raw, int width) {
        if (raw == null) {
            return null;
        }
        String hex = raw.replace("-", "").trim().toLowerCase();
        if (!hex.matches("[0-9a-f]+") || hex.length() > width) {
            return null;
        }
        String padded = "0".repeat(width - hex.length()) + hex;
        if (padded.chars().allMatch(ch -> ch == '0')) {
            return null;
        }
        return padded;
    }
}
