package com.aegistrace.observability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class W3cTraceContextTest {
    @Test
    void formatsSampledTraceparent() {
        assertEquals(
                "00-abc123abc123abc123abc123abc123ab-0123456789abcdef-01",
                W3cTraceContext.traceparent("ABC123ABC123ABC123ABC123ABC123AB", "123456789abcdef", true));
    }

    @Test
    void rejectsBlankAndZeroIds() {
        assertNull(W3cTraceContext.traceparent("0".repeat(32), "0123456789abcdef", true));
        assertNull(W3cTraceContext.spanId("not hex"));
        assertNull(W3cTraceContext.traceparent(null, "0123456789abcdef", true));
    }
}
