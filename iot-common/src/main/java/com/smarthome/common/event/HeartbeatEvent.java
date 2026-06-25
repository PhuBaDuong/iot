package com.smarthome.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * =============================================================================
 * HeartbeatEvent - Liveness signal from a device (Phase 2)
 * =============================================================================
 * Published by devices (the simulator) to {@code sensor.exchange} with the
 * routing key {@code device.heartbeat}. The Device Registry consumes it and
 * refreshes {@code lastSeenAt}, promoting the device to {@code ACTIVE}.
 * =============================================================================
 */
public record HeartbeatEvent(
        String sensorId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp) {
}
