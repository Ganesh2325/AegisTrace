package com.aegistrace.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class CorrelationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = header(request, "X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        String traceId = header(request, "X-Trace-Id");
        if (traceId == null) {
            traceId = requestId;
        }
        MDC.put("request_id", requestId);
        MDC.put("trace_id", traceId);
        MDC.put("service", "control-plane");
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.length() > 80) {
            return null;
        }
        return value;
    }
}
