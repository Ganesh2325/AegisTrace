package com.aegistrace.security;

import com.aegistrace.common.CorrelationFilter;
import com.aegistrace.common.OriginFilter;
import com.aegistrace.config.AppProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter, InternalTokenFilter internalTokenFilter,
                                    OriginFilter originFilter, CorrelationFilter correlationFilter,
                                    SecurityHeadersFilter securityHeadersFilter, AppProperties properties) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication is required.\"}");
                }))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/liveness", "/readiness", "/health").permitAll()
                            .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    if (!properties.isProduction()) {
                        auth.requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll();
                    }
                    auth.requestMatchers("/internal/**").hasRole("INTERNAL")
                            .anyRequest().authenticated();
                })
                .addFilterBefore(securityHeadersFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(correlationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(originFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(internalTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilterRegistration(CorrelationFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<OriginFilter> originFilterRegistration(OriginFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(InternalTokenFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<JwtAuthFilter> jwtFilterRegistration(JwtAuthFilter filter) {
        return disabled(filter);
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> securityHeadersFilterRegistration(SecurityHeadersFilter filter) {
        return disabled(filter);
    }

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
