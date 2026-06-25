package com.smarthome.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * =============================================================================
 * DeviceRegistrationRequest - Payload to register/provision a device (Phase 2)
 * =============================================================================
 * Sent by the sensor-simulator-service (auto-registration on startup) and by
 * administrators to {@code POST /api/devices}. The registry creates the device
 * in the {@code PROVISIONED} state.
 * =============================================================================
 */
public record DeviceRegistrationRequest(
        @NotBlank(message = "sensorId is required") String sensorId,
        @NotBlank(message = "name is required") String name,
        @NotNull(message = "sensorType is required") SensorType sensorType,
        @NotBlank(message = "location is required") String location,
        String firmwareVersion,
        String owner) {
}
