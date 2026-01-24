package com.smarthome.processing.controller;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.processing.model.SensorStatistics;
import com.smarthome.processing.service.AlertHandlerService;
import com.smarthome.processing.service.AnalyticsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Analytics REST Controller
 * 
 * Exposes REST APIs for querying analytics data and system health.
 * These endpoints enable:
 * - Dashboard visualization of sensor statistics
 * - Monitoring and alerting integration
 * - Third-party system integration
 * 
 * API Design Notes:
 * - All endpoints are read-only (GET methods)
 * - Responses are JSON formatted
 * - No authentication in this example (add Spring Security in production)
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final AlertHandlerService alertHandlerService;

    public AnalyticsController(AnalyticsService analyticsService, AlertHandlerService alertHandlerService) {
        this.analyticsService = analyticsService;
        this.alertHandlerService = alertHandlerService;
    }

    /**
     * Get statistics for all sensors.
     * 
     * @return Collection of sensor statistics
     */
    @GetMapping("/statistics")
    public ResponseEntity<Collection<SensorStatistics>> getAllStatistics() {
        log.debug("Request received: GET /api/analytics/statistics");
        Collection<SensorStatistics> stats = analyticsService.getAllStatistics();
        return ResponseEntity.ok(stats);
    }

    /**
     * Get statistics for a specific sensor.
     * 
     * @param sensorId The sensor identifier
     * @return Sensor statistics or 404 if not found
     */
    @GetMapping("/statistics/{sensorId}")
    public ResponseEntity<SensorStatistics> getSensorStatistics(@PathVariable String sensorId) {
        log.debug("Request received: GET /api/analytics/statistics/{}", sensorId);
        
        return analyticsService.getStatistics(sensorId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get recent alerts.
     * 
     * @param limit Maximum number of alerts to return (default: 50)
     * @return List of recent alerts
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<AlertEvent>> getRecentAlerts(
            @RequestParam(defaultValue = "50") int limit) {
        log.debug("Request received: GET /api/analytics/alerts?limit={}", limit);
        List<AlertEvent> alerts = alertHandlerService.getRecentAlerts(limit);
        return ResponseEntity.ok(alerts);
    }

    /**
     * Health check endpoint.
     * Returns system health and basic metrics.
     * 
     * Used by:
     * - Kubernetes liveness/readiness probes
     * - Load balancer health checks
     * - Monitoring systems
     * 
     * @return Health status and metrics
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> healthCheck() {
        Map<String, Object> health = new HashMap<>();
        
        health.put("status", "UP");
        health.put("service", "processing-service");
        health.put("timestamp", Instant.now().toString());
        
        // Include basic metrics
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sensorsTracked", analyticsService.getSensorCount());
        metrics.put("totalReadingsProcessed", analyticsService.getTotalReadingsProcessed());
        metrics.put("recentAlertsCount", alertHandlerService.getAlertCount());
        health.put("metrics", metrics);
        
        return ResponseEntity.ok(health);
    }

    /**
     * Get summary analytics.
     * Provides an overview of the system's current state.
     * 
     * @return Summary of analytics data
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> getSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        summary.put("sensorsTracked", analyticsService.getSensorCount());
        summary.put("totalReadingsProcessed", analyticsService.getTotalReadingsProcessed());
        summary.put("alertsCount", alertHandlerService.getAlertCount());
        summary.put("statistics", analyticsService.getAllStatistics());
        summary.put("generatedAt", Instant.now().toString());
        
        return ResponseEntity.ok(summary);
    }
}

