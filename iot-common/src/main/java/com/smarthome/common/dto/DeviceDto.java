package com.smarthome.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * =============================================================================
 * DeviceDto - Shared device contract (Phase 2)
 * =============================================================================
 * The REST representation of a device returned by the Device Registry and
 * consumed by the gateway (cached in Redis) and any other client. Keyed by the
 * business identifier {@code sensorId} that appears on every {@code SensorReading}.
 * =============================================================================
 */
public record DeviceDto(
        String sensorId,
        String name,
        SensorType sensorType,
        String location,
        String firmwareVersion,
        DeviceStatus status,
        String owner,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant provisionedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant lastSeenAt) {
}
