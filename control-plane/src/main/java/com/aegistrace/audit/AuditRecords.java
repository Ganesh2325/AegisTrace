package com.aegistrace.audit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuditRecords {
    private static final Set<String> BLOCKED = Set.of(
            "password", "token", "secret", "apikey", "api_key", "authorization",
            "prompt", "systemprompt", "system_prompt", "question", "content",
            "quote", "arguments", "body", "ticketbody", "raw");

    private AuditRecords() {}

    public static String actorKind(UUID actorId) {
        return actorId == null ? "SYSTEM" : "HUMAN";
    }

    public static String result(String action, Map<String, Object> metadata) {
        if ("LOGIN_FAILED".equals(action)) {
            return "FAILED";
        }
        if ("POLICY_DECISION".equals(action) && metadata != null) {
            Object decision = metadata.get("decision");
            if ("DENY".equals(String.valueOf(decision))) {
                return "DENIED";
            }
        }
        return "SUCCESS";
    }

    public static Map<String, Object> sanitize(Map<String, Object> metadata) {
        var out = new LinkedHashMap<String, Object>();
        if (metadata == null) {
            return out;
        }
        for (var entry : metadata.entrySet()) {
            String key = entry.getKey();
            if (key == null || blocked(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text && text.length() > 240) {
                out.put(key, text.substring(0, 240));
            } else if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                continue;
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    public static String approvalId(String resourceType, String resourceId, Map<String, Object> metadata) {
        if ("approval".equals(resourceType) && resourceId != null && !resourceId.isBlank()) {
            return resourceId;
        }
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get("approvalId");
        return value == null ? null : String.valueOf(value);
    }

    private static boolean blocked(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace("-", "_");
        if (BLOCKED.contains(normalized)) {
            return true;
        }
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("prompt");
    }
}
