package com.smarthome.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * =============================================================================
 * Threshold Configuration for Anomaly Detection
 * =============================================================================
 *
 * LEARNING NOTE: @ConfigurationProperties
 * ----------------------------------------
 * This class binds to properties prefixed with "gateway.thresholds" in
 * application.yml. Spring Boot automatically:
 * 1. Creates a bean of this class
 * 2. Populates fields from configuration
 * 3. Supports type-safe access to configuration
 *
 * Example configuration:
 *   gateway:
 *     thresholds:
 *       temperature:
 *         min: 15.0
 *         max: 30.0
 *
 * Benefits over @Value:
 * - Type-safe and validated
 * - Grouped, hierarchical configuration
 * - IDE autocomplete support
 * - Easy to test with different values
 *
 * =============================================================================
 */
@ConfigurationProperties(prefix = "gateway.thresholds")
public class ThresholdConfig {

    /**
     * Threshold settings for temperature sensor.
     */
    private ThresholdRange temperature = new ThresholdRange(15.0, 30.0);

    /**
     * Threshold settings for humidity sensor.
     */
    private ThresholdRange humidity = new ThresholdRange(30.0, 70.0);

    /**
     * Threshold settings for light sensor.
     */
    private ThresholdRange light = new ThresholdRange(0.0, 10000.0);

    /**
     * Threshold settings for pressure sensor.
     */
    private ThresholdRange pressure = new ThresholdRange(950.0, 1050.0);

    public ThresholdRange getTemperature() {
        return temperature;
    }

    public void setTemperature(ThresholdRange temperature) {
        this.temperature = temperature;
    }

    public ThresholdRange getHumidity() {
        return humidity;
    }

    public void setHumidity(ThresholdRange humidity) {
        this.humidity = humidity;
    }

    public ThresholdRange getLight() {
        return light;
    }

    public void setLight(ThresholdRange light) {
        this.light = light;
    }

    public ThresholdRange getPressure() {
        return pressure;
    }

    public void setPressure(ThresholdRange pressure) {
        this.pressure = pressure;
    }

    /**
     * Get threshold range for a specific sensor type.
     *
     * @param sensorType The type of sensor (e.g., "TEMPERATURE", "HUMIDITY")
     * @return The threshold range, or null if not configured
     */
    public ThresholdRange getThresholdForType(String sensorType) {
        Map<String, ThresholdRange> thresholds = new HashMap<>();
        thresholds.put("TEMPERATURE", temperature);
        thresholds.put("HUMIDITY", humidity);
        thresholds.put("LIGHT", light);
        thresholds.put("PRESSURE", pressure);
        return thresholds.get(sensorType.toUpperCase());
    }

    /**
     * Inner class representing a min/max threshold range.
     */
    public static class ThresholdRange {
        private double min;
        private double max;

        public ThresholdRange() {
        }

        public ThresholdRange(double min, double max) {
            this.min = min;
            this.max = max;
        }

        public double getMin() {
            return min;
        }

        public void setMin(double min) {
            this.min = min;
        }

        public double getMax() {
            return max;
        }

        public void setMax(double max) {
            this.max = max;
        }

        /**
         * Check if a value is within the threshold range.
         */
        public boolean isWithinRange(double value) {
            return value >= min && value <= max;
        }

        /**
         * Check if a value exceeds the maximum threshold.
         */
        public boolean exceedsMax(double value) {
            return value > max;
        }

        /**
         * Check if a value is below the minimum threshold.
         */
        public boolean belowMin(double value) {
            return value < min;
        }
    }
}

