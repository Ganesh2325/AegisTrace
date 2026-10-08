package com.aegistrace.config;

import com.aegistrace.security.RequestRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RedisConfig {
    @Bean(destroyMethod = "close")
    RedisAccess redisAccess(AppProperties properties) {
        return RedisAccess.open(properties);
    }

    @Bean
    RequestRateLimiter requestRateLimiter(RedisAccess redis) {
        var commands = redis.commands();
        if (commands == null) {
            return new RequestRateLimiter();
        }
        return new RequestRateLimiter(new RequestRateLimiter.RedisCounter(commands));
    }
}
