package com.smarthome.gateway.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * =============================================================================
 * Deduplication Service (Redis-backed)
 * =============================================================================
 * With publisher confirms and listener retries enabled, the same reading can be
 * delivered more than once. We use Redis {@code SET key value EX ttl NX} to get
 * lightweight exactly-once semantics: the first delivery creates the key, any
 * subsequent delivery within the TTL window is detected as a duplicate.
 * =============================================================================
 */
@Service
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);
    private static final String KEY_PREFIX = "reading:";

    private final StringRedisTemplate redisTemplate;
    private final Counter deduplicated;
    private final Duration ttl;

    public DeduplicationService(StringRedisTemplate redisTemplate,
                                MeterRegistry meterRegistry,
                                @Value("${gateway.dedup.ttl-seconds:300}") long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.deduplicated = Counter.builder("gateway.readings.deduplicated")
                .description("Total duplicate readings discarded").register(meterRegistry);
    }

    /**
     * @return {@code true} if this is the first time we have seen the reading
     *         (process it); {@code false} if it is a duplicate (discard it).
     *         If Redis is unavailable we fail open and treat the reading as new.
     */
    public boolean isFirstSeen(String readingId) {
        if (readingId == null || readingId.isBlank()) {
            return true;
        }
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + readingId, "1", ttl);
            boolean firstSeen = Boolean.TRUE.equals(created);
            if (!firstSeen) {
                deduplicated.increment();
                log.debug("Duplicate reading discarded: {}", readingId);
            }
            return firstSeen;
        } catch (Exception e) {
            log.warn("Deduplication check failed for {} (failing open): {}", readingId, e.getMessage());
            return true;
        }
    }
}
