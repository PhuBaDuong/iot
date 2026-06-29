package com.smarthome.common.constants;

/**
 * =============================================================================
 * RedisConstants - Shared Redis keys and pub/sub channels (Phase 2)
 * =============================================================================
 * Centralizes the Redis contract shared between the Device Registry (writer)
 * and the gateway (reader/subscriber) so the two services never drift apart.
 * =============================================================================
 */
public final class RedisConstants {

    private RedisConstants() {
    }

    /**
     * Pub/sub channel the Device Registry publishes to whenever a device's
     * lifecycle status changes. The message body is the affected {@code sensorId}.
     * The gateway subscribes and evicts that entry from its device-status cache
     * so a state change takes effect immediately instead of waiting for the TTL.
     */
    public static final String DEVICE_STATUS_CHANGED_CHANNEL = "device.status.changed";
}
