package com.aegistrace.security;

import com.aegistrace.common.CorrelationFilter;
import com.aegistrace.common.OriginFilter;
import com.aegistrace.security.InternalTokenFilter;
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
                                    OriginFilter originFilter, CorrelationFilter correlationFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/liveness", "/readiness", "/health").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/internal/**").hasRole("INTERNAL")
                        .anyRequest().authenticated())
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

    private static <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabled(T filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
