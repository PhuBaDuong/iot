package com.smarthome.registry.exception;

/**
 * Thrown when a device lookup/update targets a {@code sensorId} that is not
 * registered. Mapped to HTTP 404 by {@code RestExceptionHandler}.
 */
public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException(String sensorId) {
        super("Device not found: " + sensorId);
    }
}
