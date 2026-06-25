package com.smarthome.gateway.service;

import com.smarthome.common.dto.DeviceDto;
import com.smarthome.common.dto.DeviceStatus;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * =============================================================================
 * DeviceRegistryGateway - resilient REST access to the Device Registry
 * =============================================================================
 * Wrapped with Resilience4j {@code @CircuitBreaker}/{@code @Retry} (instance
 * {@code deviceRegistryBreaker}). It lives in its own bean - separate from the
 * cached {@link DeviceRegistryClient} - so Spring AOP can proxy both the cache
 * and the circuit-breaker advice independently (self-invocation would bypass
 * one of them).
 *
 * A 404 is a definitive "unknown device" answer, not a failure, so it is caught
 * here and never trips the breaker. Transport/5xx errors propagate and, once the
 * breaker opens, {@link #lookupFallback} returns {@code UNVERIFIED} (fail open).
 * =============================================================================
 */
@Service
public class DeviceRegistryGateway {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistryGateway.class);
    private static final String INSTANCE = "deviceRegistryBreaker";

    private final RestClient deviceRegistryRestClient;

    public DeviceRegistryGateway(RestClient deviceRegistryRestClient) {
        this.deviceRegistryRestClient = deviceRegistryRestClient;
    }

    @CircuitBreaker(name = INSTANCE, fallbackMethod = "lookupFallback")
    @Retry(name = INSTANCE)
    public DeviceAvailability lookup(String sensorId) {
        try {
            DeviceDto device = deviceRegistryRestClient.get()
                    .uri("/api/devices/{sensorId}", sensorId)
                    .retrieve()
                    .body(DeviceDto.class);
            if (device == null || device.status() == null) {
                return DeviceAvailability.UNKNOWN;
            }
            return device.status() == DeviceStatus.DECOMMISSIONED
                    ? DeviceAvailability.DECOMMISSIONED
                    : DeviceAvailability.ALLOWED;
        } catch (HttpClientErrorException.NotFound notFound) {
            return DeviceAvailability.UNKNOWN;
        }
    }

    /** Circuit-open / transport-failure fallback: fail open as UNVERIFIED. */
    @SuppressWarnings("unused")
    private DeviceAvailability lookupFallback(String sensorId, Throwable t) {
        log.warn("Device Registry lookup failed for {} ({}); failing open as UNVERIFIED",
                sensorId, t.toString());
        return DeviceAvailability.UNVERIFIED;
    }
}
