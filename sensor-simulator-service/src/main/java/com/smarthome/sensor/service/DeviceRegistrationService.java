package com.smarthome.sensor.service;

import com.smarthome.common.dto.DeviceRegistrationRequest;
import com.smarthome.sensor.config.SensorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * =============================================================================
 * DeviceRegistrationService - auto-registers simulated sensors (Phase 2)
 * =============================================================================
 * On startup (and on a retry schedule until it succeeds) each simulated sensor
 * is registered with the Device Registry via {@code POST /api/devices}.
 * Registration is idempotent on the registry side, and we track which sensors
 * have been confirmed so the retry only targets the ones still pending - this
 * tolerates the registry not being up yet when the simulator starts.
 * =============================================================================
 */
@Service
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);
    private static final String FIRMWARE_VERSION = "1.2.3";
    private static final String OWNER = "sensor-simulator";

    private final RestClient deviceRegistryRestClient;
    private final SensorConfig sensorConfig;
    private final Set<String> registered = Collections.synchronizedSet(new HashSet<>());

    public DeviceRegistrationService(RestClient deviceRegistryRestClient, SensorConfig sensorConfig) {
        this.deviceRegistryRestClient = deviceRegistryRestClient;
        this.sensorConfig = sensorConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        registerPending();
    }

    /** Retries registration for any sensors not yet confirmed registered. */
    @Scheduled(fixedDelayString = "${simulation.registration.retry-interval:30000}")
    public void registerPending() {
        for (SensorConfig.SensorDefinition sensor : sensorConfig.getSensors()) {
            if (registered.contains(sensor.getId())) {
                continue;
            }
            try {
                DeviceRegistrationRequest request = new DeviceRegistrationRequest(
                        sensor.getId(), sensor.getId(), sensor.getType(), sensor.getLocation(),
                        FIRMWARE_VERSION, OWNER);
                deviceRegistryRestClient.post()
                        .uri("/api/devices")
                        .body(request)
                        .retrieve()
                        .toBodilessEntity();
                registered.add(sensor.getId());
                log.info("Registered sensor with Device Registry: {}", sensor.getId());
            } catch (RuntimeException e) {
                log.warn("Device registration pending for {} ({}); will retry",
                        sensor.getId(), e.getMessage());
            }
        }
    }
}
