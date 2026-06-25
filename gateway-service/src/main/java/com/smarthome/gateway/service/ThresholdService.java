package com.smarthome.gateway.service;

import com.smarthome.gateway.config.ThresholdConfig;
import com.smarthome.gateway.config.ThresholdConfig.ThresholdRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * =============================================================================
 * Threshold Service (Redis-backed with YAML fallback)
 * =============================================================================
 * Anomaly-detection thresholds need to be tunable at runtime without a redeploy
 * (e.g. raising the temperature ceiling during a heatwave). We store overrides
 * in a Redis hash per sensor type:
 *
 *   config:thresholds:TEMPERATURE -> { min: 15.0, max: 30.0 }
 *
 * Lookups prefer Redis; if no override exists (or Redis is unavailable) we fall
 * back to the static {@link ThresholdConfig} bound from {@code application.yml}.
 * Updates go through {@link #updateThreshold} (exposed via the admin endpoint).
 * =============================================================================
 */
@Service
public class ThresholdService {

    private static final Logger log = LoggerFactory.getLogger(ThresholdService.class);
    private static final String KEY_PREFIX = "config:thresholds:";
    private static final String FIELD_MIN = "min";
    private static final String FIELD_MAX = "max";

    private final StringRedisTemplate redisTemplate;
    private final ThresholdConfig thresholdConfig;

    public ThresholdService(StringRedisTemplate redisTemplate, ThresholdConfig thresholdConfig) {
        this.redisTemplate = redisTemplate;
        this.thresholdConfig = thresholdConfig;
    }

    /**
     * Returns the active threshold for a sensor type, preferring a Redis
     * override and falling back to the YAML-bound configuration.
     */
    public ThresholdRange getThreshold(String sensorType) {
        if (sensorType == null) {
            return null;
        }
        String key = KEY_PREFIX + sensorType.toUpperCase();
        try {
            Map<Object, Object> entries = redisTemplate.opsForHash().entries(key);
            Object min = entries.get(FIELD_MIN);
            Object max = entries.get(FIELD_MAX);
            if (min != null && max != null) {
                return new ThresholdRange(
                        Double.parseDouble(min.toString()),
                        Double.parseDouble(max.toString()));
            }
        } catch (Exception e) {
            log.warn("Threshold lookup from Redis failed for {} (falling back to YAML): {}",
                    sensorType, e.getMessage());
        }
        return thresholdConfig.getThresholdForType(sensorType);
    }

    /**
     * Persists a threshold override for a sensor type to Redis.
     *
     * @return the stored {@link ThresholdRange}
     */
    public ThresholdRange updateThreshold(String sensorType, double min, double max) {
        String key = KEY_PREFIX + sensorType.toUpperCase();
        redisTemplate.opsForHash().put(key, FIELD_MIN, Double.toString(min));
        redisTemplate.opsForHash().put(key, FIELD_MAX, Double.toString(max));
        log.info("Threshold override updated for {}: [{}, {}]", sensorType.toUpperCase(), min, max);
        return new ThresholdRange(min, max);
    }
}
