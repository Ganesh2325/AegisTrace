package com.aegistrace.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentSnapshots {
    private AgentSnapshots() {}

    public static Map<String, Object> withoutPrompt(Map<String, Object> snapshot) {
        var copy = new LinkedHashMap<String, Object>();
        if (snapshot == null) {
            return copy;
        }
        snapshot.forEach((key, value) -> {
            if (!"systemPrompt".equals(key)) {
                copy.put(key, value);
            }
        });
        return copy;
    }

    public static Object versionMarker(Map<String, Object> snapshot) {
        return snapshot == null ? null : snapshot.get("version");
    }
}
