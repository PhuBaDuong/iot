package com.smarthome.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;

/**
 * =============================================================================
 * SensorReading - The Core Message in Our System
 * =============================================================================
 *
 * LEARNING NOTE: Data Transfer Objects (DTOs)
 * -------------------------------------------
 * This class represents a message that flows through our system:
 *
 * 1. Sensor Simulator creates a SensorReading
 * 2. Serializes it to JSON
 * 3. Publishes to RabbitMQ
 * 4. Gateway Service receives and deserializes it
 * 5. Validates and enriches the data
 * 6. Republishes for further processing
 *
 * Key considerations for message DTOs:
 * - Must be serializable (Jackson handles JSON conversion)
 * - Should be immutable or have clear ownership
 * - Include timestamps for ordering and debugging
 * - Include correlation IDs for tracing
 *
 * =============================================================================
 */
public class SensorReading {

    @NotBlank(message = "Reading ID is required")
    private String readingId;

    @NotBlank(message = "Sensor ID is required")
    private String sensorId;

    @NotNull(message = "Sensor type is required")
    private SensorType sensorType;

    @NotNull(message = "Value is required")
    private Double value;

    private String unit;

    @NotBlank(message = "Location is required")
    private String location;

    @NotNull(message = "Timestamp is required")
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant timestamp;

    private Map<String, Object> metadata;

    public SensorReading() {}

    public SensorReading(String readingId, String sensorId, SensorType sensorType,
                         Double value, String unit, String location,
                         Instant timestamp, Map<String, Object> metadata) {
        this.readingId = readingId;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
        this.location = location;
        this.timestamp = timestamp;
        this.metadata = metadata;
    }

    // Getters and Setters
    public String getReadingId() { return readingId; }
    public void setReadingId(String readingId) { this.readingId = readingId; }
    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public SensorType getSensorType() { return sensorType; }
    public void setSensorType(SensorType sensorType) { this.sensorType = sensorType; }
    public Double getValue() { return value; }
    public void setValue(Double value) { this.value = value; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    // Builder pattern
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String readingId;
        private String sensorId;
        private SensorType sensorType;
        private Double value;
        private String unit;
        private String location;
        private Instant timestamp;
        private Map<String, Object> metadata;

        public Builder readingId(String readingId) { this.readingId = readingId; return this; }
        public Builder sensorId(String sensorId) { this.sensorId = sensorId; return this; }
        public Builder sensorType(SensorType sensorType) { this.sensorType = sensorType; return this; }
        public Builder value(Double value) { this.value = value; return this; }
        public Builder unit(String unit) { this.unit = unit; return this; }
        public Builder location(String location) { this.location = location; return this; }
        public Builder timestamp(Instant timestamp) { this.timestamp = timestamp; return this; }
        public Builder metadata(Map<String, Object> metadata) { this.metadata = metadata; return this; }

        public SensorReading build() {
            return new SensorReading(readingId, sensorId, sensorType, value, unit, location, timestamp, metadata);
        }
    }
}

