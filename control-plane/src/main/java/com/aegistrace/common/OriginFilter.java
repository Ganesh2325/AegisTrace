package com.aegistrace.common;

import com.aegistrace.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OriginFilter extends OncePerRequestFilter {
    private final Set<String> allowed;

    public OriginFilter(AppProperties properties) {
        this.allowed = Arrays.stream(properties.getFrontendOrigin().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/internal/") || uri.equals("/liveness") || uri.equals("/readiness") || uri.equals("/health")) {
            return true;
        }
        return HttpMethod.GET.matches(request.getMethod())
                || HttpMethod.HEAD.matches(request.getMethod())
                || HttpMethod.OPTIONS.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String origin = request.getHeader("Origin");
        if (origin != null && allowed.contains(origin)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(403);
        response.setContentType("application/json");
        response.getOutputStream().write(
                "{\"code\":\"ORIGIN_REJECTED\",\"message\":\"Origin is not allowed.\"}".getBytes(StandardCharsets.UTF_8));
    }
}
