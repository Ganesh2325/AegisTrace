package com.aegistrace.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ProductionGuard {
    private ProductionGuard() {}

    public static List<String> problems(AppProperties properties, Map<String, String> env) {
        if (!properties.isProduction()) {
            return List.of();
        }
        var problems = new ArrayList<String>();
        if (properties.isSeedEnabled()) {
            problems.add("seed data is enabled");
        }
        if (properties.isFailureSimulationEnabled()) {
            problems.add("failure simulation is enabled");
        }
        if (!properties.isCookieSecure()) {
            problems.add("session cookie is not Secure");
        }
        rejectSecret(problems, "AEGIS_JWT_SECRET", properties.getJwtSecret(), 32);
        rejectSecret(problems, "AEGIS_INTERNAL_TOKEN", properties.getInternalToken(), 24);
        String origin = properties.getFrontendOrigin() == null ? "" : properties.getFrontendOrigin();
        if (!origin.startsWith("https://")) {
            problems.add("frontend origin must use https");
        }
        String runtime = properties.getRuntimeUrl() == null ? "" : properties.getRuntimeUrl().toLowerCase(Locale.ROOT);
        if (runtime.isBlank() || runtime.contains("localhost") || runtime.contains("127.0.0.1")) {
            problems.add("runtime URL must be a private service address");
        }
        if (!properties.getRedis().isEnabled()) {
            problems.add("Redis is required for shared rate limiting");
        }
        rejectSecret(problems, "REDIS_PASSWORD", properties.getRedis().getPassword(), 12);
        var storage = properties.getS3();
        if (storage.getBucket() == null || storage.getBucket().isBlank()) {
            problems.add("object storage bucket is required");
        }
        String endpoint = storage.getEndpoint() == null ? "" : storage.getEndpoint().trim().toLowerCase(Locale.ROOT);
        if (!endpoint.isBlank()) {
            problems.add("production object storage must use AWS S3 through the task role");
        }
        if (present(storage.getAccessKey()) || present(storage.getSecretKey())) {
            problems.add("production object storage must not use static access keys");
        }
        String databaseUrl = value(env, "DATABASE_URL").toLowerCase(Locale.ROOT);
        if (databaseUrl.isBlank() || databaseUrl.contains("change-me") || databaseUrl.contains("localhost") || databaseUrl.contains("127.0.0.1")) {
            problems.add("database URL is local or still a development secret");
        }
        String databasePassword = value(env, "POSTGRES_PASSWORD");
        if (databasePassword.toLowerCase(Locale.ROOT).contains("change-me") || databasePassword.equals("aegis") || databasePassword.length() < 12) {
            problems.add("database password is a development default");
        }
        return List.copyOf(problems);
    }

    public static void failIfUnsafe(AppProperties properties, Map<String, String> env) {
        var problems = problems(properties, env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start production: " + String.join("; ", problems));
        }
    }

    private static void rejectSecret(List<String> problems, String name, String secret, int minimum) {
        if (secret == null || secret.length() < minimum || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            problems.add(name + " is missing or still a development secret");
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String value(Map<String, String> env, String key) {
        String found = env.get(key);
        return found == null ? "" : found;
    }
}
