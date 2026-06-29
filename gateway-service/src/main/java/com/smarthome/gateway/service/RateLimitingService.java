package com.smarthome.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * =============================================================================
 * Rate Limiting Service (Redis-backed)
 * =============================================================================
 * A misbehaving or compromised device can flood the gateway. We cap how many
 * readings a single sensor may send per minute using a fixed-window counter:
 *
 *   INCR ratelimit:{sensorId}:{minute}
 *   EXPIRE ratelimit:{sensorId}:{minute} 120   (only on first increment)
 *
 * The key embeds the current minute so the window rolls over automatically and
 * stale keys expire on their own. Readings beyond the limit are rejected and
 * dead-lettered by the caller.
 * =============================================================================
 */
@Service
public class RateLimitingService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitingService.class);
    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;
    private final Counter rateLimited;
    private final int maxPerMinute;

    public RateLimitingService(StringRedisTemplate redisTemplate,
                               MeterRegistry meterRegistry,
                               @Value("${gateway.rate-limit.max-per-minute:120}") int maxPerMinute) {
        this.redisTemplate = redisTemplate;
        this.maxPerMinute = maxPerMinute;
        this.rateLimited = Counter.builder("gateway.readings.rate_limited")
                .description("Total readings rejected because the per-device rate limit was exceeded")
                .register(meterRegistry);
    }

    /**
     * @return {@code true} if the sensor is within its per-minute budget;
     *         {@code false} if it has exceeded the limit (reject the reading).
     *         If Redis is unavailable we fail open and allow the reading.
     */
    public boolean isAllowed(String sensorId) {
        if (sensorId == null || sensorId.isBlank()) {
            return true;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        String key = KEY_PREFIX + sensorId + ":" + minute;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count != null && count == 1L) {
                // First hit in this window: set a TTL slightly longer than the window.
                redisTemplate.expire(key, Duration.ofSeconds(120));
            }
            boolean allowed = count == null || count <= maxPerMinute;
            if (!allowed) {
                rateLimited.increment();
                log.warn("Rate limit exceeded for sensor {} ({} readings in current window, limit {})",
                        sensorId, count, maxPerMinute);
            }
            return allowed;
        } catch (Exception e) {
            log.warn("Rate-limit check failed for {} (failing open): {}", sensorId, e.getMessage());
            return true;
        }
    }
}
