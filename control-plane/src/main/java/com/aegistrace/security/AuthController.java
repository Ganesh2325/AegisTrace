package com.aegistrace.security;

import com.aegistrace.common.ApiException;
import com.aegistrace.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {
    private final AuthService authService;
    private final Rbac rbac;
    private final AppProperties properties;

    AuthController(AuthService authService, Rbac rbac, AppProperties properties) {
        this.authService = authService;
        this.rbac = rbac;
        this.properties = properties;
    }

    @PostMapping("/login")
    Map<String, Object> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        var session = authService.login(request.email(), request.password());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(session.token(), Duration.ofHours(8)).toString());
        return session.body();
    }

    @PostMapping("/logout")
    Map<String, Object> logout(HttpServletResponse response) {
        authService.logout(rbac.current());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString());
        return Map.of("status", "logged_out");
    }

    @GetMapping("/me")
    Map<String, Object> me() {
        return authService.me(rbac.current());
    }

    private ResponseCookie cookie(String value, Duration age) {
        return ResponseCookie.from("aegis_session", value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(age)
                .build();
    }

    public record LoginRequest(String email, String password) {}
}

@org.springframework.stereotype.Service
class AuthService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final com.aegistrace.audit.AuditService audit;

    AuthService(NamedParameterJdbcTemplate jdbc, PasswordEncoder passwordEncoder, JwtService jwtService,
                com.aegistrace.audit.AuditService audit) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
    }

    Session login(String email, String password) {
        if (email == null || password == null) {
            throw new ApiException("VALIDATION_FAILED", "Email and password are required.", 400);
        }
        String normalized = email.trim().toLowerCase();
        Long failures = jdbc.queryForObject(
                "select count(*) from login_failures where email = :email and created_at > now() - interval '10 minutes'",
                Map.of("email", normalized), Long.class);
        if (failures != null && failures >= 10) {
            throw new ApiException("RATE_LIMITED", "Too many login attempts. Try again later.", 429);
        }
        var users = jdbc.query(
                "select id, password_hash, display_name, status from users where email = :email",
                Map.of("email", normalized),
                (rs, n) -> new String[]{rs.getString("id"), rs.getString("password_hash"), rs.getString("display_name"), rs.getString("status")});
        if (users.isEmpty() || !"ACTIVE".equals(users.get(0)[3]) || !passwordEncoder.matches(password, users.get(0)[1])) {
            jdbc.update("insert into login_failures (email) values (:email)", Map.of("email", normalized));
            audit.record(null, null, "LOGIN_FAILED", "user", normalized, null, Map.of());
            throw new ApiException("INVALID_CREDENTIALS", "Email or password is incorrect.", 401);
        }
        UUID userId = UUID.fromString(users.get(0)[0]);
        jdbc.update("delete from login_failures where email = :email", Map.of("email", normalized));
        Actor actor = loadActor(userId);
        audit.record(actor.memberships().isEmpty() ? null : actor.memberships().get(0).workspaceId(), userId,
                "LOGIN", "user", userId.toString(), null, Map.of());
        MDC.put("user_id", userId.toString());
        return new Session(jwtService.issue(userId), me(actor));
    }

    void logout(Actor actor) {
        UUID workspace = actor.memberships().isEmpty() ? null : actor.memberships().get(0).workspaceId();
        audit.record(workspace, actor.id(), "LOGOUT", "user", actor.id().toString(), null, Map.of());
    }

    Map<String, Object> me(Actor actor) {
        return Map.of(
                "id", actor.id(),
                "email", actor.email(),
                "displayName", actor.displayName(),
                "memberships", actor.memberships().stream().map(m -> Map.of(
                        "workspaceId", m.workspaceId(),
                        "role", m.role()
                )).toList()
        );
    }

    Actor loadActor(UUID userId) {
        var users = jdbc.query(
                "select id, email, display_name, status from users where id = :id",
                Map.of("id", userId),
                (rs, n) -> new Actor(UUID.fromString(rs.getString("id")), rs.getString("email"),
                        rs.getString("display_name"), rs.getString("status"), List.of()));
        if (users.isEmpty()) {
            return null;
        }
        var memberships = jdbc.query(
                "select workspace_id, role from memberships where user_id = :id",
                Map.of("id", userId),
                (rs, n) -> new Actor.Membership(UUID.fromString(rs.getString("workspace_id")), rs.getString("role")));
        Actor base = users.get(0);
        return new Actor(base.id(), base.email(), base.displayName(), base.status(), memberships);
    }

    record Session(String token, Map<String, Object> body) {}
}
