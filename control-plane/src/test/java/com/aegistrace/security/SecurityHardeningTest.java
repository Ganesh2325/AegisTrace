package com.aegistrace.security;

import com.aegistrace.common.ApiException;
import com.aegistrace.config.AppProperties;
import com.aegistrace.knowledge.KnowledgeService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityHardeningTest {
    @Test
    void apiResponsesCarryBaselineHeaders() throws Exception {
        var properties = new AppProperties();
        properties.setCookieSecure(true);
        var response = new MockHttpServletResponse();
        new SecurityHeadersFilter(properties).doFilter(new MockHttpServletRequest(), response, (request, ignored) -> {});
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("default-src 'none'; frame-ancestors 'none'; base-uri 'none'", response.getHeader("Content-Security-Policy"));
        assertEquals("max-age=31536000; includeSubDomains", response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void localHttpDoesNotEmitStrictTransportSecurity() throws Exception {
        var response = new MockHttpServletResponse();
        new SecurityHeadersFilter(new AppProperties()).doFilter(new MockHttpServletRequest(), response, (request, ignored) -> {});
        assertNull(response.getHeader("Strict-Transport-Security"));
    }

    @Test
    void documentTitlesCannotCarryPathsOrControlCharacters() {
        assertEquals("policy.md", KnowledgeService.displayTitle(null, "../../secret/policy.md"));
        assertEquals("<script>alert(1)</script>", KnowledgeService.displayTitle("<script>alert(1)</script>", null));
        assertEquals("Untitled document", KnowledgeService.displayTitle(" \u0000 ", "../"));
    }

    @Test
    void expensiveRequestsAreBoundedPerActor() {
        var limiter = new RequestRateLimiter();
        limiter.acquire("retrieval:user", 2);
        limiter.acquire("retrieval:user", 2);
        ApiException ex = assertThrows(ApiException.class, () -> limiter.acquire("retrieval:user", 2));
        assertEquals(429, ex.getStatus());
        assertEquals("RATE_LIMITED", ex.getCode());
    }

    @Test
    void sharedCounterEnforcesOneLimitAcrossInstances() {
        var counter = new RequestRateLimiter.MemoryCounter();
        var first = new RequestRateLimiter(counter);
        var second = new RequestRateLimiter(counter);
        first.acquire("upload:user", 1);
        ApiException ex = assertThrows(ApiException.class, () -> second.acquire("upload:user", 1));
        assertEquals(429, ex.getStatus());
    }
}
