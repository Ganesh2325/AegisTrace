package com.aegistrace.security;

import com.aegistrace.config.AppProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Component
public class InternalTokenFilter extends OncePerRequestFilter {
    private final byte[] expected;

    public InternalTokenFilter(AppProperties properties) {
        this.expected = properties.getInternalToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presented = request.getHeader("X-Internal-Token");
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(actual, expected)) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Internal token is invalid.\"}");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                "internal", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }
}
