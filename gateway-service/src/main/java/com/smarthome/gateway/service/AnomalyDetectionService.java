package com.smarthome.gateway.service;

import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.dto.SensorType;
import com.smarthome.gateway.config.ThresholdConfig;
import com.smarthome.gateway.config.ThresholdConfig.ThresholdRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * =============================================================================
 * Anomaly Detection Service - Identifies Abnormal Sensor Readings
 * =============================================================================
 * 
 * LEARNING NOTE: Threshold-Based Anomaly Detection
 * -------------------------------------------------
 * This is a simple but effective anomaly detection approach:
 * 
 * 1. Define "normal" operating ranges for each sensor type
 * 2. Compare incoming readings against these thresholds
 * 3. Flag readings outside the range as anomalies
 * 
 * More sophisticated approaches include:
 * - Statistical methods (standard deviation, z-scores)
 * - Machine learning models
 * - Time-series analysis (sudden changes)
 * - Contextual analysis (time of day, season)
 * 
 * For a home IoT system, simple thresholds work well and are:
 * - Easy to understand and configure
 * - Fast to compute
 * - Deterministic (same input = same output)
 * 
 * =============================================================================
 */
@Service
public class AnomalyDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyDetectionService.class);

    private final ThresholdConfig thresholdConfig;

    public AnomalyDetectionService(ThresholdConfig thresholdConfig) {
        this.thresholdConfig = thresholdConfig;
    }

    /**
     * Check if a sensor reading represents an anomaly.
     *
     * @param reading The sensor reading to check
     * @return Optional containing AnomalyDetails if anomaly detected, empty otherwise
     */
    public Optional<AnomalyDetails> detectAnomaly(SensorReading reading) {
        if (reading == null || reading.getSensorType() == null || reading.getValue() == null) {
            log.warn("Cannot detect anomaly: reading has null fields");
            return Optional.empty();
        }

        SensorType sensorType = reading.getSensorType();
        double value = reading.getValue();
        
        // Get configured thresholds for this sensor type
        ThresholdRange threshold = thresholdConfig.getThresholdForType(sensorType.name());
        
        if (threshold == null) {
            // No threshold configured for this sensor type
            log.debug("No threshold configured for sensor type: {}", sensorType);
            return Optional.empty();
        }

        // Check if value is within the configured threshold
        if (threshold.isWithinRange(value)) {
            return Optional.empty();
        }

        // Value is outside threshold - this is an anomaly!
        AnomalyDetails anomaly = buildAnomalyDetails(reading, threshold, value);
        
        log.info("Anomaly detected! Sensor: {}, Type: {}, Value: {}, Threshold: [{}, {}]",
                reading.getSensorId(),
                sensorType,
                value,
                threshold.getMin(),
                threshold.getMax());

        return Optional.of(anomaly);
    }

    /**
     * Build anomaly details with descriptive information.
     */
    private AnomalyDetails buildAnomalyDetails(SensorReading reading, 
                                                ThresholdRange threshold, 
                                                double value) {
        String direction;
        double exceededThreshold;
        Severity severity;

        if (threshold.exceedsMax(value)) {
            direction = "above";
            exceededThreshold = threshold.getMax();
            // Calculate severity based on how much it exceeds
            double percentOver = ((value - threshold.getMax()) / threshold.getMax()) * 100;
            severity = percentOver > 20 ? Severity.CRITICAL : Severity.WARNING;
        } else {
            direction = "below";
            exceededThreshold = threshold.getMin();
            double percentUnder = ((threshold.getMin() - value) / threshold.getMin()) * 100;
            severity = percentUnder > 20 ? Severity.CRITICAL : Severity.WARNING;
        }

        String description = String.format(
            "%s reading of %.2f%s is %s the threshold of %.2f%s",
            reading.getSensorType().name(),
            value,
            reading.getUnit() != null ? reading.getUnit() : "",
            direction,
            exceededThreshold,
            reading.getUnit() != null ? reading.getUnit() : ""
        );

        return new AnomalyDetails(
            true,
            description,
            severity,
            exceededThreshold,
            value
        );
    }

    /**
     * Severity levels for anomalies.
     */
    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    /**
     * Details about a detected anomaly.
     */
    public record AnomalyDetails(
        boolean isAnomaly,
        String description,
        Severity severity,
        double threshold,
        double actualValue
    ) {}
}

