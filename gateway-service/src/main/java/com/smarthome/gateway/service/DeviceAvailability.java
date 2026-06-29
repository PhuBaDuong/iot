package com.smarthome.gateway.service;

/**
 * =============================================================================
 * DeviceAvailability - the gateway's verdict on a reading's source device
 * =============================================================================
 * Derived from a Device Registry lookup (cached for 60s):
 *
 *   ALLOWED        device is registered and not decommissioned -> accept
 *   UNKNOWN        no such device in the registry              -> reject (DLQ)
 *   DECOMMISSIONED device is retired                           -> reject (DLQ)
 *   UNVERIFIED     registry unreachable (circuit open)         -> fail open, accept
 *
 * UNVERIFIED is deliberately never cached so the gateway re-probes the registry
 * as soon as it recovers.
 * =============================================================================
 */
public enum DeviceAvailability {
    ALLOWED,
    UNKNOWN,
    DECOMMISSIONED,
    UNVERIFIED;

    /** Whether a reading from a device in this state should be processed. */
    public boolean isAccepted() {
        return this == ALLOWED || this == UNVERIFIED;
    }
}
