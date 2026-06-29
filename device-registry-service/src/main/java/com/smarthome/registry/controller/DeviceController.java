package com.smarthome.registry.controller;

import com.smarthome.common.dto.DeviceDto;
import com.smarthome.common.dto.DeviceRegistrationRequest;
import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.registry.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * =============================================================================
 * DeviceController - REST API for device registration and lifecycle
 * =============================================================================
 * The gateway calls {@code GET /api/devices/{sensorId}} to validate readings.
 * The simulator calls {@code POST /api/devices} to auto-register on startup.
 * Administrators use the status/decommission/delete endpoints.
 * =============================================================================
 */
@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    /** Register (or idempotently re-register) a device. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceDto register(@Valid @RequestBody DeviceRegistrationRequest request) {
        return deviceService.register(request);
    }

    @GetMapping
    public List<DeviceDto> list() {
        return deviceService.listDevices();
    }

    @GetMapping("/{sensorId}")
    public DeviceDto get(@PathVariable String sensorId) {
        return deviceService.getDevice(sensorId);
    }

    /** Admin status transition, e.g. {@code PUT /api/devices/{id}/status?value=INACTIVE}. */
    @PutMapping("/{sensorId}/status")
    public DeviceDto updateStatus(@PathVariable String sensorId, @RequestParam("value") DeviceStatus value) {
        return deviceService.updateStatus(sensorId, value);
    }

    @PostMapping("/{sensorId}/decommission")
    public DeviceDto decommission(@PathVariable String sensorId) {
        return deviceService.decommission(sensorId);
    }

    @DeleteMapping("/{sensorId}")
    public ResponseEntity<Void> delete(@PathVariable String sensorId) {
        deviceService.delete(sensorId);
        return ResponseEntity.noContent().build();
    }
}
