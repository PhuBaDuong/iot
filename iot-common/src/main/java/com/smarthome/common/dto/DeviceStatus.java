package com.smarthome.common.dto;

/**
 * =============================================================================
 * DeviceStatus - Lifecycle states for a registered device (Phase 2)
 * =============================================================================
 * State machine enforced by the Device Registry:
 *
 *   PROVISIONED -> ACTIVE -> INACTIVE <-> ACTIVE -> DECOMMISSIONED
 *
 * - PROVISIONED:    registered but not yet seen sending data/heartbeats
 * - ACTIVE:         seen recently (within the heartbeat silence window)
 * - INACTIVE:       silent past the window; auto-set by the inactivity sweep
 * - DECOMMISSIONED: retired; readings from such devices are rejected
 * =============================================================================
 */
public enum DeviceStatus {
    PROVISIONED,
    ACTIVE,
    INACTIVE,
    DECOMMISSIONED
}
