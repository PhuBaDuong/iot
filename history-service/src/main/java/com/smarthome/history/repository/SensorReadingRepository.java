package com.smarthome.history.repository;

import com.smarthome.history.entity.SensorReadingEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * =============================================================================
 * SensorReadingRepository - Spring Data JPA access to the hypertable
 * =============================================================================
 */
public interface SensorReadingRepository extends JpaRepository<SensorReadingEntity, String> {

    /** Readings for a sensor within a time window, newest first. */
    List<SensorReadingEntity> findBySensorIdAndTimeBetweenOrderByTimeDesc(
            String sensorId, Instant from, Instant to, Pageable pageable);

    /** Most recent readings for a sensor, newest first. */
    List<SensorReadingEntity> findBySensorIdOrderByTimeDesc(String sensorId, Pageable pageable);
}
