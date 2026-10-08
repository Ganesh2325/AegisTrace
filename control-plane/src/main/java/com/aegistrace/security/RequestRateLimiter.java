package com.aegistrace.security;

import com.aegistrace.common.ApiException;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestRateLimiter {
    private final Counter counter;

    public RequestRateLimiter() {
        this(new MemoryCounter());
    }

    public RequestRateLimiter(Counter counter) {
        this.counter = counter;
    }

    public void acquire(String key, int limitPerMinute) {
        if (counter.increment(key, 60) > limitPerMinute) {
            throw new ApiException("RATE_LIMITED", "Too many requests. Try again later.", 429);
        }
    }

    public interface Counter {
        long increment(String key, int ttlSeconds);
    }

    public static final class MemoryCounter implements Counter {
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

        @Override
        public long increment(String key, int ttlSeconds) {
            long now = System.currentTimeMillis();
            Window window = windows.compute(key, (ignored, existing) -> {
                if (existing == null || now - existing.startedAtMs > ttlSeconds * 1000L) {
                    return new Window(now, new AtomicInteger(1));
                }
                existing.count.incrementAndGet();
                return existing;
            });
            if (windows.size() > 10_000) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMs > ttlSeconds * 1000L);
            }
            return window.count.get();
        }

        private record Window(long startedAtMs, AtomicInteger count) {}
    }

    public static final class RedisCounter implements Counter {
        private final RedisCommands<String, String> commands;

        public RedisCounter(RedisCommands<String, String> commands) {
            this.commands = commands;
        }

        @Override
        public long increment(String key, int ttlSeconds) {
            String redisKey = "aegis:rate:" + key;
            Long count = commands.incr(redisKey);
            if (count != null && count == 1L) {
                commands.expire(redisKey, ttlSeconds);
            }
            return count == null ? 1L : count;
        }
    }
}
