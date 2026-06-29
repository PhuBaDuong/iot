package com.smarthome.sensor.controller;

import com.smarthome.sensor.config.SensorConfig;
import com.smarthome.sensor.service.SensorSimulatorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * =============================================================================
 * Simulator Controller - REST API for Simulation Control
 * =============================================================================
 *
 * LEARNING NOTE: REST APIs in Microservices
 * ------------------------------------------
 * Even in event-driven architectures, REST APIs are useful for:
 * 1. Health checks and status monitoring
 * 2. Manual control and debugging
 * 3. Integration with orchestration tools
 * 4. Exposing metrics and configuration
 *
 * This controller provides endpoints to:
 * - Check simulation status
 * - Start/stop the simulation
 * - Manually trigger sensor readings
 *
 * =============================================================================
 */
@RestController
@RequestMapping("/api/simulator")
public class SimulatorController {

    private final SensorSimulatorService simulatorService;
    private final SensorConfig sensorConfig;

    public SimulatorController(SensorSimulatorService simulatorService, SensorConfig sensorConfig) {
        this.simulatorService = simulatorService;
        this.sensorConfig = sensorConfig;
    }

    /**
     * GET /api/simulator/status
     * Returns the current status of the simulation.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", simulatorService.isRunning());
        status.put("interval", sensorConfig.getInterval());
        status.put("sensorCount", sensorConfig.getSensors().size());
        status.put("sensors", sensorConfig.getSensors());
        
        return ResponseEntity.ok(status);
    }

    /**
     * POST /api/simulator/trigger
     * Manually triggers a single round of sensor readings.
     * Useful for testing without waiting for the scheduled interval.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerReading() {
        // Publish readings for all configured sensors
        sensorConfig.getSensors().forEach(simulatorService::publishReading);

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Triggered readings for all sensors");

        response.put("sensorCount", sensorConfig.getSensors().size());
        
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/simulator/start
     * Starts the automatic simulation.
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> startSimulation() {
        simulatorService.start();
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Simulation started");
        response.put("status", "running");
        
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/simulator/stop
     * Stops the automatic simulation.
     * Note: This only stops the scheduled task; manual triggers still work.
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, String>> stopSimulation() {
        simulatorService.stop();
        
        Map<String, String> response = new HashMap<>();
        response.put("message", "Simulation stopped");
        response.put("status", "stopped");
        
        return ResponseEntity.ok(response);
    }
}

