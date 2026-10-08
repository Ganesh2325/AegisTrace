package com.aegistrace.health;

import com.aegistrace.config.RedisAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
class HealthController {
    private final JdbcTemplate jdbc;
    private final RedisAccess redis;

    HealthController(JdbcTemplate jdbc, RedisAccess redis) {
        this.jdbc = jdbc;
        this.redis = redis;
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
        if (!redis.isEnabled()) {
            body.put("redis", "OPTIONAL");
        } else if (redis.ping()) {
            body.put("redis", "UP");
        } else {
            body.put("redis", "DOWN");
            up = false;
        }
        body.put("status", up ? "UP" : "DOWN");
        return ResponseEntity.status(up ? 200 : 503).body(body);
    }
}
