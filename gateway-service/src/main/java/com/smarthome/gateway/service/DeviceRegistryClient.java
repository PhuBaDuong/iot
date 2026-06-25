package com.smarthome.gateway.service;

import com.smarthome.gateway.config.RedisConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * =============================================================================
 * DeviceRegistryClient - cached device-availability lookups
 * =============================================================================
 * Caches the {@link DeviceAvailability} verdict per {@code sensorId} in the
 * Redis-backed {@code deviceStatus} cache (60s TTL, see {@link RedisConfig}).
 * {@code UNVERIFIED} (registry unreachable) is excluded from caching so the
 * gateway re-probes promptly once the registry recovers.
 *
 * The actual REST call - guarded by the circuit breaker - is delegated to the
 * separate {@link DeviceRegistryGateway} bean so both the cache and the
 * circuit-breaker proxies are applied.
 * =============================================================================
 */
@Service
public class DeviceRegistryClient {

    private final DeviceRegistryGateway gateway;

    public DeviceRegistryClient(DeviceRegistryGateway gateway) {
        this.gateway = gateway;
    }

    @Cacheable(value = RedisConfig.DEVICE_STATUS_CACHE, key = "#sensorId",
            unless = "#result == T(com.smarthome.gateway.service.DeviceAvailability).UNVERIFIED")
    public DeviceAvailability getAvailability(String sensorId) {
        return gateway.lookup(sensorId);
    }
}
