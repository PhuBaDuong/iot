package com.smarthome.gateway.service;

import com.smarthome.gateway.config.ThresholdConfig;
import com.smarthome.gateway.config.ThresholdConfig.ThresholdRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThresholdService}. Redis is mocked so the tests verify
 * the Redis-override / YAML-fallback precedence and the update path.
 */
@ExtendWith(MockitoExtension.class)
class ThresholdServiceTest {

    private static final String KEY = "config:thresholds:TEMPERATURE";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private ThresholdConfig thresholdConfig;
    private ThresholdService service;

    @BeforeEach
    void setUp() {
        // YAML-bound defaults: temperature range is 15.0 .. 30.0
        thresholdConfig = new ThresholdConfig();
        service = new ThresholdService(redisTemplate, thresholdConfig);
    }

    @Test
    void redisOverrideTakesPrecedenceOverYaml() {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("min", "10.0");
        entries.put("max", "40.0");
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(KEY)).thenReturn(entries);

        ThresholdRange range = service.getThreshold("temperature");

        assertThat(range.getMin()).isEqualTo(10.0);
        assertThat(range.getMax()).isEqualTo(40.0);
    }

    @Test
    void fallsBackToYamlWhenNoOverridePresent() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(KEY)).thenReturn(new HashMap<>());

        ThresholdRange range = service.getThreshold("TEMPERATURE");

        assertThat(range.getMin()).isEqualTo(15.0);
        assertThat(range.getMax()).isEqualTo(30.0);
    }

    @Test
    void fallsBackToYamlWhenRedisFails() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(KEY)).thenThrow(new RuntimeException("redis down"));

        ThresholdRange range = service.getThreshold("temperature");

        assertThat(range.getMin()).isEqualTo(15.0);
        assertThat(range.getMax()).isEqualTo(30.0);
    }

    @Test
    void nullSensorTypeReturnsNull() {
        assertThat(service.getThreshold(null)).isNull();
    }

    @Test
    void updateThresholdPersistsOverrideToRedis() {
        when(redisTemplate.opsForHash()).thenReturn(hashOps);

        ThresholdRange range = service.updateThreshold("temperature", 12.5, 35.5);

        assertThat(range.getMin()).isEqualTo(12.5);
        assertThat(range.getMax()).isEqualTo(35.5);
        verify(hashOps).put(KEY, "min", "12.5");
        verify(hashOps).put(KEY, "max", "35.5");
    }
}
