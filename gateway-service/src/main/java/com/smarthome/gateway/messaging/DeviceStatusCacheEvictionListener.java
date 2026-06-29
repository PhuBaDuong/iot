package com.smarthome.gateway.messaging;

import com.smarthome.gateway.config.RedisConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * DeviceStatusCacheEvictionListener - keeps the device cache fresh
 * =============================================================================
 * Subscribes to the Redis {@code device.status.changed} channel that the Device
 * Registry publishes to. The message body is the affected {@code sensorId};
 * receiving it evicts that entry from the gateway's {@code deviceStatus} cache
 * so a lifecycle change (e.g. DECOMMISSIONED) takes effect immediately instead
 * of waiting out the 60s TTL.
 * =============================================================================
 */
@Component
public class DeviceStatusCacheEvictionListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(DeviceStatusCacheEvictionListener.class);

    private final CacheManager cacheManager;
    private final Counter evictions;

    public DeviceStatusCacheEvictionListener(CacheManager cacheManager, MeterRegistry meterRegistry) {
        this.cacheManager = cacheManager;
        this.evictions = Counter.builder("gateway.device.cache_evictions")
                .description("Device-status cache evictions triggered by registry events")
                .register(meterRegistry);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String sensorId = new String(message.getBody(), StandardCharsets.UTF_8);
        if (sensorId.isBlank()) {
            return;
        }
        Cache cache = cacheManager.getCache(RedisConfig.DEVICE_STATUS_CACHE);
        if (cache != null) {
            cache.evictIfPresent(sensorId);
            evictions.increment();
            log.debug("Evicted device-status cache entry for {}", sensorId);
        }
    }
}
