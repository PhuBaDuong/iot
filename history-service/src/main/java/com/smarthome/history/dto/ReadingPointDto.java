package com.smarthome.history.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.smarthome.history.entity.SensorReadingEntity;

import java.time.Instant;

/**
 * =============================================================================
 * ReadingPointDto - REST representation of a stored reading
 * =============================================================================
 * A flat, serialization-friendly view of {@link SensorReadingEntity} exposed by
 * the history query API.
 * =============================================================================
 */
public record ReadingPointDto(
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant time,
        String readingId,
        String sensorId,
        String sensorType,
        Double value,
        String unit,
        String location,
        boolean anomaly,
        String anomalyDescription) {

    public static ReadingPointDto from(SensorReadingEntity e) {
        return new ReadingPointDto(e.getTime(), e.getReadingId(), e.getSensorId(), e.getSensorType(),
                e.getValue(), e.getUnit(), e.getLocation(), e.isAnomaly(), e.getAnomalyDescription());
    }
}
