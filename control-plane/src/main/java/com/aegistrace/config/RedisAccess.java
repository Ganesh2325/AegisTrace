package com.aegistrace.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;

import java.time.Duration;

public final class RedisAccess implements AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;

    private RedisAccess(RedisClient client, StatefulRedisConnection<String, String> connection) {
        this.client = client;
        this.connection = connection;
    }

    public static RedisAccess open(AppProperties properties) {
        if (!properties.getRedis().isEnabled()) {
            if (properties.isProduction()) {
                throw new IllegalStateException("Refusing to start production without Redis");
            }
            return new RedisAccess(null, null);
        }
        RedisClient client = null;
        try {
            var builder = RedisURI.builder()
                    .withHost(properties.getRedis().getHost())
                    .withPort(properties.getRedis().getPort())
                    .withTimeout(Duration.ofSeconds(2));
            String password = properties.getRedis().getPassword();
            if (password != null && !password.isBlank()) {
                builder.withPassword(password.toCharArray());
            }
            client = RedisClient.create(builder.build());
            var connection = client.connect();
            connection.setTimeout(Duration.ofSeconds(2));
            connection.sync().ping();
            return new RedisAccess(client, connection);
        } catch (RuntimeException ex) {
            if (client != null) {
                client.shutdown();
            }
            if (properties.isProduction()) {
                throw new IllegalStateException("Refusing to start production because Redis is unavailable", ex);
            }
            return new RedisAccess(null, null);
        }
    }

    public boolean isEnabled() {
        return connection != null;
    }

    public RedisCommands<String, String> commands() {
        return connection == null ? null : connection.sync();
    }

    public boolean ping() {
        if (connection == null) {
            return false;
        }
        try {
            return "PONG".equalsIgnoreCase(connection.sync().ping());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
    }
}
