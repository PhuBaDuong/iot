package com.smarthome.gateway.controller;

import com.smarthome.gateway.listener.SensorDataListener;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * =============================================================================
 * Gateway Controller - REST API for Gateway Service
 * =============================================================================
 * 
 * LEARNING NOTE: Health Checks and Metrics
 * -----------------------------------------
 * In microservices, REST endpoints for health and metrics are essential for:
 * 
 * 1. HEALTH CHECKS:
 *    - Kubernetes liveness/readiness probes
 *    - Load balancer health monitoring
 *    - Service discovery health status
 * 
 * 2. METRICS/STATISTICS:
 *    - Operational visibility
 *    - Performance monitoring
 *    - Debugging production issues
 *    - Capacity planning
 * 
 * In production, you would typically use:
 * - Spring Boot Actuator for standard endpoints
 * - Micrometer for metrics export to Prometheus, DataDog, etc.
 * - Distributed tracing with Zipkin or Jaeger
 * 
 * =============================================================================
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    private final SensorDataListener sensorDataListener;

    public GatewayController(SensorDataListener sensorDataListener) {
        this.sensorDataListener = sensorDataListener;
    }

    /**
     * Health check endpoint.
     * 
     * LEARNING NOTE: Health Check Best Practices
     * ------------------------------------------
     * A good health check should:
     * 1. Be fast (< 1 second response time)
     * 2. Check critical dependencies (database, message queue)
     * 3. Return appropriate HTTP status codes
     * 4. Include timestamp for debugging
     * 
     * @return Health status response
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "gateway-service",
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Processing statistics endpoint.
     * 
     * LEARNING NOTE: Operational Metrics
     * -----------------------------------
     * These statistics help answer operational questions:
     * - How many messages are we processing? (throughput)
     * - How many errors are occurring? (error rate)
     * - How many anomalies are we detecting? (anomaly rate)
     * 
     * For production monitoring, export these to a metrics system
     * and set up alerts for abnormal values.
     * 
     * @return Statistics response
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        long received = sensorDataListener.getMessagesReceived();
        long processed = sensorDataListener.getMessagesProcessed();
        long failures = sensorDataListener.getValidationFailures();
        long anomalies = sensorDataListener.getAnomaliesDetected();

        // Calculate rates (as percentages)
        double validationFailureRate = received > 0 
                ? (failures * 100.0) / received 
                : 0.0;
        double anomalyRate = processed > 0 
                ? (anomalies * 100.0) / processed 
                : 0.0;

        return ResponseEntity.ok(Map.of(
                "messagesReceived", received,
                "messagesProcessed", processed,
                "validationFailures", failures,
                "anomaliesDetected", anomalies,
                "validationFailureRate", String.format("%.2f%%", validationFailureRate),
                "anomalyRate", String.format("%.2f%%", anomalyRate),
                "timestamp", Instant.now().toString()
        ));
    }
}

