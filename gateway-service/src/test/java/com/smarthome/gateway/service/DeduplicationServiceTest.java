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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeduplicationService}. Redis is mocked so the tests
 * exercise the dedup decision logic and metrics without a live broker.
 */
@ExtendWith(MockitoExtension.class)
class DeduplicationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry meterRegistry;
    private DeduplicationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new DeduplicationService(redisTemplate, meterRegistry, 300);
    }

    @Test
    void firstDeliveryIsProcessed() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("reading:abc"), eq("1"), any(Duration.class))).thenReturn(true);

        assertThat(service.isFirstSeen("abc")).isTrue();
        assertThat(deduplicatedCount()).isZero();
    }

    @Test
    void duplicateDeliveryIsDiscardedAndCounted() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("reading:abc"), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(service.isFirstSeen("abc")).isFalse();
        assertThat(deduplicatedCount()).isEqualTo(1.0);
    }

    @Test
    void blankReadingIdIsTreatedAsNewWithoutTouchingRedis() {
        assertThat(service.isFirstSeen("")).isTrue();
        assertThat(service.isFirstSeen(null)).isTrue();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void redisFailureFailsOpen() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("redis down"));

        assertThat(service.isFirstSeen("abc")).isTrue();
        assertThat(deduplicatedCount()).isZero();
    }

    private double deduplicatedCount() {
        return meterRegistry.get("gateway.readings.deduplicated").counter().count();
    }
}
