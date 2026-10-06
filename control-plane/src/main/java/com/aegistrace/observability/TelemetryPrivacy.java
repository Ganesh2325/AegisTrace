package com.aegistrace.observability;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class TelemetryPrivacy {
    static final Pattern TRACE_ID = Pattern.compile("^[a-fA-F0-9]{16,32}$");
    private static final Set<String> BLOCKED = Set.of(
            "password", "token", "secret", "apikey", "authorization",
            "prompt", "systemprompt", "question", "content", "quote",
            "arguments", "body", "http.url", "db.statement");

    private TelemetryPrivacy() {}

    public static boolean safeTraceId(String traceId) {
        return traceId != null && TRACE_ID.matcher(traceId.replace("-", "")).matches();
    }

    public static String hexTraceId(String traceId) {
        if (traceId == null) {
            return "";
        }
        return traceId.replace("-", "");
    }

    public static String jaegerTraceUrl(String publicBase, String traceId) {
        if (publicBase == null || publicBase.isBlank() || !safeTraceId(hexTraceId(traceId))) {
            return null;
        }
        String base = publicBase.trim();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return null;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/trace/" + hexTraceId(traceId);
    }

    public static Map<String, Object> attributes(Map<String, Object> raw) {
        var out = new LinkedHashMap<String, Object>();
        if (raw == null) {
            return out;
        }
        int count = 0;
        for (var entry : raw.entrySet()) {
            if (count >= 40) {
                break;
            }
            String key = entry.getKey();
            if (key == null || blocked(key)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof String text) {
                if (text.length() > 200) {
                    continue;
                }
                out.put(key, text);
            } else if (value instanceof Number || value instanceof Boolean) {
                out.put(key, value);
            } else {
                continue;
            }
            count++;
        }
        return out;
    }

    public static boolean blocked(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace(".", "").replace("-", "_");
        if (BLOCKED.contains(key.toLowerCase(Locale.ROOT)) || BLOCKED.contains(normalized)) {
            return true;
        }
        return normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("prompt")
                || normalized.contains("user_id") || normalized.equals("userid");
    }
}
