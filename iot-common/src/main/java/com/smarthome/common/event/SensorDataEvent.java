package com.smarthome.common.event;

import com.smarthome.common.dto.SensorReading;
import java.time.Instant;

/**
 * =============================================================================
 * SensorDataEvent - Enriched Event After Gateway Processing
 * =============================================================================
 *
 * LEARNING NOTE: Events vs DTOs
 * -----------------------------
 * - DTO (SensorReading): Raw data from the sensor
 * - Event (SensorDataEvent): Enriched data with processing metadata
 *
 * Events typically include:
 * - The original data
 * - Processing timestamps
 * - Validation status
 * - Enrichment data (e.g., calculated fields)
 *
 * This separation allows us to:
 * 1. Keep raw data intact for auditing
 * 2. Add processing context without modifying original
 * 3. Track the journey of data through the system
 *
 * =============================================================================
 */
public class SensorDataEvent {

    private SensorReading reading;
    private Instant processedAt;
    private String processedBy;
    private boolean valid;
    private String validationError;
    private boolean anomaly;
    private String anomalyDescription;
    private String correlationId;

    public SensorDataEvent() {}

    public SensorDataEvent(SensorReading reading, Instant processedAt, String processedBy,
                           boolean valid, String validationError, boolean anomaly,
                           String anomalyDescription, String correlationId) {
        this.reading = reading;
        this.processedAt = processedAt;
        this.processedBy = processedBy;
        this.valid = valid;
        this.validationError = validationError;
        this.anomaly = anomaly;
        this.anomalyDescription = anomalyDescription;
        this.correlationId = correlationId;
    }

    // Getters and Setters
    public SensorReading getReading() { return reading; }
    public void setReading(SensorReading reading) { this.reading = reading; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }
    public boolean isValid() { return valid; }
    public void setValid(boolean valid) { this.valid = valid; }
    public String getValidationError() { return validationError; }
    public void setValidationError(String validationError) { this.validationError = validationError; }
    public boolean isAnomaly() { return anomaly; }
    public void setAnomaly(boolean anomaly) { this.anomaly = anomaly; }
    public String getAnomalyDescription() { return anomalyDescription; }
    public void setAnomalyDescription(String anomalyDescription) { this.anomalyDescription = anomalyDescription; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private SensorReading reading;
        private Instant processedAt;
        private String processedBy;
        private boolean valid;
        private String validationError;
        private boolean anomaly;
        private String anomalyDescription;
        private String correlationId;

        public Builder reading(SensorReading reading) { this.reading = reading; return this; }
        public Builder processedAt(Instant processedAt) { this.processedAt = processedAt; return this; }
        public Builder processedBy(String processedBy) { this.processedBy = processedBy; return this; }
        public Builder valid(boolean valid) { this.valid = valid; return this; }
        public Builder validationError(String validationError) { this.validationError = validationError; return this; }
        public Builder anomaly(boolean anomaly) { this.anomaly = anomaly; return this; }
        public Builder anomalyDescription(String anomalyDescription) { this.anomalyDescription = anomalyDescription; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }

        public SensorDataEvent build() {
            return new SensorDataEvent(reading, processedAt, processedBy, valid, validationError, anomaly, anomalyDescription, correlationId);
        }
    }
}

