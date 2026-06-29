package com.smarthome.gateway.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitingService}. Redis is mocked so the tests
 * exercise the fixed-window counter logic and the over-limit metric.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitingServiceTest {

    private static final int MAX_PER_MINUTE = 5;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry meterRegistry;
    private RateLimitingService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new RateLimitingService(redisTemplate, meterRegistry, MAX_PER_MINUTE);
    }

    @Test
    void firstHitSetsExpiryAndIsAllowed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(service.isAllowed("sensor-1")).isTrue();
        verify(redisTemplate).expire(anyString(), eq(Duration.ofSeconds(120)));
        assertThat(rateLimitedCount()).isZero();
    }

    @Test
    void countAtLimitIsAllowedWithoutResettingExpiry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn((long) MAX_PER_MINUTE);

        assertThat(service.isAllowed("sensor-1")).isTrue();
        verify(redisTemplate, times(0)).expire(anyString(), any(Duration.class));
        assertThat(rateLimitedCount()).isZero();
    }

    @Test
    void countOverLimitIsRejectedAndCounted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn((long) (MAX_PER_MINUTE + 1));

        assertThat(service.isAllowed("sensor-1")).isFalse();
        assertThat(rateLimitedCount()).isEqualTo(1.0);
    }

    @Test
    void blankSensorIdIsAllowedWithoutTouchingRedis() {
        assertThat(service.isAllowed("")).isTrue();
        assertThat(service.isAllowed(null)).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThat(service.isAllowed("sensor-1")).isTrue();
        assertThat(rateLimitedCount()).isZero();
    }

    private double rateLimitedCount() {
        return meterRegistry.get("gateway.readings.rate_limited").counter().count();
    }
}
