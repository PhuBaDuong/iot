package com.smarthome.common.event;

import com.smarthome.common.dto.SensorReading;
import java.time.Instant;

/**
 * =============================================================================
 * AlertEvent - Notification for Anomalies
 * =============================================================================
 *
 * LEARNING NOTE: Event-Driven Alerts
 * -----------------------------------
 * When an anomaly is detected, we publish an AlertEvent to a separate queue.
 * This allows:
 * 1. Dedicated alert consumers (email, SMS, push notifications)
 * 2. Alert aggregation and deduplication
 * 3. Different processing priorities (alerts are high priority)
 *
 * =============================================================================
 */
public class AlertEvent {

    public enum Severity { INFO, WARNING, CRITICAL }

    private String alertId;
    private SensorReading triggeringReading;
    private Severity severity;
    private String message;
    private Instant timestamp;
    private Double threshold;
    private Double actualValue;
    private String correlationId;

    public AlertEvent() {}

    public AlertEvent(String alertId, SensorReading triggeringReading, Severity severity,
                      String message, Instant timestamp, Double threshold,
                      Double actualValue, String correlationId) {
        this.alertId = alertId;
        this.triggeringReading = triggeringReading;
        this.severity = severity;
        this.message = message;
        this.timestamp = timestamp;
        this.threshold = threshold;
        this.actualValue = actualValue;
        this.correlationId = correlationId;
    }

    // Getters and Setters
    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }
    public SensorReading getTriggeringReading() { return triggeringReading; }
    public void setTriggeringReading(SensorReading triggeringReading) { this.triggeringReading = triggeringReading; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Double getThreshold() { return threshold; }
    public void setThreshold(Double threshold) { this.threshold = threshold; }
    public Double getActualValue() { return actualValue; }
    public void setActualValue(Double actualValue) { this.actualValue = actualValue; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String alertId;
        private SensorReading triggeringReading;
        private Severity severity;
        private String message;
        private Instant timestamp;
        private Double threshold;
        private Double actualValue;
        private String correlationId;

        public Builder alertId(String alertId) { this.alertId = alertId; return this; }
        public Builder triggeringReading(SensorReading triggeringReading) { this.triggeringReading = triggeringReading; return this; }
        public Builder severity(Severity severity) { this.severity = severity; return this; }
        public Builder message(String message) { this.message = message; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder threshold(Double threshold) { this.threshold = threshold; return this; }
        public Builder actualValue(Double actualValue) { this.actualValue = actualValue; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }

        public AlertEvent build() {
            return new AlertEvent(alertId, triggeringReading, severity, message, timestamp, threshold, actualValue, correlationId);
        }
    }
}

