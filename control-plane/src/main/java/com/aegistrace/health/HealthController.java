package com.aegistrace.health;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
class HealthController {
    private final JdbcTemplate jdbc;

    HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/liveness")
    Map<String, Object> liveness() {
        return Map.of("status", "UP", "service", "control-plane");
    }

    @GetMapping({"/readiness", "/health"})
    ResponseEntity<Map<String, Object>> readiness() {
        var body = new LinkedHashMap<String, Object>();
        body.put("service", "control-plane");
        boolean up;
        try {
            jdbc.queryForObject("select 1", Integer.class);
            body.put("postgres", "UP");
            up = true;
        } catch (Exception ex) {
            body.put("postgres", "DOWN");
            up = false;
        }
        body.put("status", up ? "UP" : "DOWN");
        body.put("redis", "OPTIONAL");
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }
}
