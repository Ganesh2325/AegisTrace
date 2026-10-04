package com.aegistrace.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.postgresql.util.PGobject;

import java.util.Map;

public final class Jsons {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private Jsons() {}

    public static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ApiException("INTERNAL", "Could not encode JSON.", 500);
        }
    }

    public static Map<String, Object> map(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, MAP);
        } catch (JsonProcessingException e) {
            throw new ApiException("INTERNAL", "Could not read JSON.", 500);
        }
    }

    public static PGobject jsonb(String json) {
        try {
            var object = new PGobject();
            object.setType("jsonb");
            object.setValue(json);
            return object;
        } catch (java.sql.SQLException e) {
            throw new ApiException("INTERNAL", "Could not encode JSON.", 500);
        }
    }
}
