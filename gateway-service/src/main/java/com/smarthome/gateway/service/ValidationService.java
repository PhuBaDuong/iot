package com.smarthome.gateway.service;

import com.smarthome.common.dto.SensorReading;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * =============================================================================
 * Validation Service - Ensures Data Integrity
 * =============================================================================
 * 
 * LEARNING NOTE: Input Validation in Message Processing
 * ------------------------------------------------------
 * When consuming messages from a queue, validation is critical because:
 * 
 * 1. Messages may come from untrusted sources
 * 2. Serialization/deserialization may produce invalid objects
 * 3. Business rules need to be enforced
 * 4. Invalid data should be rejected early (fail-fast principle)
 * 
 * Validation Strategy:
 * - Return a result object instead of throwing exceptions
 * - Collect all validation errors (not just the first one)
 * - Log validation failures for debugging and monitoring
 * 
 * =============================================================================
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    /**
     * Validates a sensor reading for completeness and correctness.
     *
     * @param reading The sensor reading to validate
     * @return ValidationResult containing success status and any error messages
     */
    public ValidationResult validate(SensorReading reading) {
        List<String> errors = new ArrayList<>();

        // Check for null reading
        if (reading == null) {
            return new ValidationResult(false, List.of("Sensor reading is null"));
        }

        // Validate required fields
        if (reading.getReadingId() == null || reading.getReadingId().isBlank()) {
            errors.add("Reading ID is required");
        }

        if (reading.getSensorId() == null || reading.getSensorId().isBlank()) {
            errors.add("Sensor ID is required");
        }

        if (reading.getSensorType() == null) {
            errors.add("Sensor type is required");
        }

        if (reading.getValue() == null) {
            errors.add("Value is required");
        } else {
            // Validate value is within sensor's physical range
            if (reading.getSensorType() != null && 
                !reading.getSensorType().isValidValue(reading.getValue())) {
                errors.add(String.format(
                    "Value %.2f is outside valid range [%.2f, %.2f] for sensor type %s",
                    reading.getValue(),
                    reading.getSensorType().getMinValue(),
                    reading.getSensorType().getMaxValue(),
                    reading.getSensorType().name()
                ));
            }
        }

        if (reading.getLocation() == null || reading.getLocation().isBlank()) {
            errors.add("Location is required");
        }

        if (reading.getTimestamp() == null) {
            errors.add("Timestamp is required");
        } else {
            // Check for future timestamps (with 1 minute tolerance for clock skew)
            Instant maxAllowedTime = Instant.now().plusSeconds(60);
            if (reading.getTimestamp().isAfter(maxAllowedTime)) {
                errors.add("Timestamp cannot be in the future");
            }

            // Check for very old timestamps (older than 24 hours)
            Instant minAllowedTime = Instant.now().minusSeconds(86400);
            if (reading.getTimestamp().isBefore(minAllowedTime)) {
                errors.add("Timestamp is too old (older than 24 hours)");
            }
        }

        // Log validation result
        if (!errors.isEmpty()) {
            log.warn("Validation failed for reading {}: {}", 
                    reading.getReadingId(), errors);
        }

        return new ValidationResult(errors.isEmpty(), errors);
    }

    /**
     * Result of validation containing status and error messages.
     */
    public record ValidationResult(boolean valid, List<String> errors) {
        
        /**
         * Get a combined error message string.
         */
        public String getErrorMessage() {
            return String.join("; ", errors);
        }
    }
}

