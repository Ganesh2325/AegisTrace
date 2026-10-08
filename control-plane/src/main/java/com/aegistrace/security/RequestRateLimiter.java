package com.aegistrace.security;

import com.aegistrace.common.ApiException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

@Component
public class RequestRateLimiter {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public void acquire(String key, int limitPerMinute) {
        long now = System.currentTimeMillis();
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAtMs > 60_000L) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAtMs > 60_000L);
        }
        if (window.count.get() > limitPerMinute) {
            throw new ApiException("RATE_LIMITED", "Too many requests. Try again later.", 429);
        }
    }

    private record Window(long startedAtMs, AtomicInteger count) {}
}
