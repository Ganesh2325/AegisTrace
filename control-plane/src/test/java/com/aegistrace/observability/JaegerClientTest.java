package com.aegistrace.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JaegerClientTest {
    @Test
    void parentPrefersChildOf() throws Exception {
        var span = new ObjectMapper().readTree("""
                {"references":[
                  {"refType":"FOLLOWS_FROM","spanID":"aaaaaaaaaaaaaaaa"},
                  {"refType":"CHILD_OF","spanID":"bbbbbbbbbbbbbbbb"}
                ]}
                """);
        assertEquals("bbbbbbbbbbbbbbbb", JaegerClient.parentSpanId(span));
    }

    @Test
    void parentIsEmptyWithoutReferences() throws Exception {
        var span = new ObjectMapper().readTree("{\"references\":[]}");
        assertEquals("", JaegerClient.parentSpanId(span));
    }
}
