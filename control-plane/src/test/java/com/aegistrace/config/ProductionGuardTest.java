package com.aegistrace.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionGuardTest {
    @Test
    void developmentKeepsLocalDefaults() {
        var properties = new AppProperties();
        properties.setEnvironment("dev");
        properties.setJwtSecret("change-me-jwt-secret-at-least-32-bytes");
        assertTrue(ProductionGuard.problems(properties, Map.of()).isEmpty());
    }

    @Test
    void productionRejectsLocalDefaults() {
        var properties = new AppProperties();
        properties.setEnvironment("prod");
        properties.setSeedEnabled(true);
        properties.setFailureSimulationEnabled(true);
        properties.setCookieSecure(false);
        properties.setJwtSecret("change-me-jwt-secret-at-least-32-bytes");
        properties.setInternalToken("change-me-internal-token");
        properties.setFrontendOrigin("http://localhost:3000");
        properties.setRuntimeUrl("http://localhost:8090");
        properties.getRedis().setEnabled(false);
        properties.getRedis().setPassword("change-me-redis-dev-only");
        properties.getS3().setEndpoint("http://garage:3900");
        properties.getS3().setAccessKey("GK000000000000000000000001");
        properties.getS3().setSecretKey("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        var problems = ProductionGuard.problems(properties, Map.of(
                "DATABASE_URL", "jdbc:postgresql://localhost:5432/aegistrace",
                "POSTGRES_PASSWORD", "change-me-postgres-dev-only"));
        assertTrue(problems.contains("seed data is enabled"));
        assertTrue(problems.contains("failure simulation is enabled"));
        assertTrue(problems.contains("session cookie is not Secure"));
        assertTrue(problems.contains("Redis is required for shared rate limiting"));
        assertTrue(problems.stream().anyMatch(item -> item.contains("AWS S3")));
        assertThrows(IllegalStateException.class, () -> ProductionGuard.failIfUnsafe(properties, Map.of(
                "DATABASE_URL", "jdbc:postgresql://localhost:5432/aegistrace",
                "POSTGRES_PASSWORD", "aegis")));
    }

    @Test
    void productionAcceptsManagedConfiguration() {
        var properties = managed();
        assertEquals(0, ProductionGuard.problems(properties, Map.of(
                "DATABASE_URL", "jdbc:postgresql://aegistrace.example.us-east-1.rds.amazonaws.com:5432/aegistrace",
                "POSTGRES_PASSWORD", "managed-database-password")).size());
    }

    private static AppProperties managed() {
        var properties = new AppProperties();
        properties.setEnvironment("production");
        properties.setSeedEnabled(false);
        properties.setFailureSimulationEnabled(false);
        properties.setCookieSecure(true);
        properties.setJwtSecret("a-production-jwt-secret-with-32-plus");
        properties.setInternalToken("a-production-internal-token-value");
        properties.setFrontendOrigin("https://app.example.com");
        properties.setRuntimeUrl("http://runtime.aegistrace.local:8090");
        properties.getRedis().setEnabled(true);
        properties.getRedis().setPassword("managed-redis-password");
        properties.getS3().setBucket("aegistrace-documents-example");
        properties.getS3().setEndpoint("");
        properties.getS3().setAccessKey("");
        properties.getS3().setSecretKey("");
        return properties;
    }
}
