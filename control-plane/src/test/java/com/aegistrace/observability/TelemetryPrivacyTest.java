package com.aegistrace.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryPrivacyTest {
    @Test
    void jaegerUrlRejectsUntrustedSchemesAndNonHexIds() {
        assertNull(TelemetryPrivacy.jaegerTraceUrl("javascript:alert(1)", "abc123abc123abc123abc123abc123ab"));
        assertNull(TelemetryPrivacy.jaegerTraceUrl("http://localhost:16686", "not-a-trace"));
        assertEquals("http://localhost:16686/trace/abc123abc123abc123abc123abc123ab",
                TelemetryPrivacy.jaegerTraceUrl("http://localhost:16686/", "abc123abc123abc123abc123abc123ab"));
    }

    @Test
    void attributesDropPromptsSecretsAndUserIds() {
        var safe = TelemetryPrivacy.attributes(Map.of(
                "run.id", "r1",
                "prompt", "secret question",
                "user.id", "u1",
                "password", "x"
        ));
        assertEquals("r1", safe.get("run.id"));
        assertFalse(safe.containsKey("prompt"));
        assertFalse(safe.containsKey("user.id"));
        assertFalse(safe.containsKey("password"));
    }

    @Test
    void parseJaegerSpansAndErrorStatus() throws Exception {
        var mapper = new ObjectMapper();
        var node = mapper.readTree("""
                {"data":[{"traceID":"abc","processes":{"p1":{"serviceName":"control-plane"}},
                  "spans":[{"spanID":"s1","operationName":"aegistrace.agent.run","startTime":1000,"duration":2000,
                    "processID":"p1","tags":[{"key":"run.id","value":"rid"},{"key":"prompt","value":"nope"},{"key":"error","value":"true"}],
                    "references":[]}]}]}
                """);
        var spans = JaegerClient.parse(node);
        assertEquals(1, spans.size());
        assertEquals("aegistrace.agent.run", spans.get(0).get("name"));
        assertEquals("ERROR", spans.get(0).get("status"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) spans.get(0).get("attributes");
        assertEquals("rid", attrs.get("run.id"));
        assertFalse(attrs.containsKey("prompt"));
        assertTrue(TelemetryPrivacy.blocked("user.id"));
    }
}
