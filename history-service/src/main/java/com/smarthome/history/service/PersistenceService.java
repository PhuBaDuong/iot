package com.smarthome.history.service;

import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.history.entity.SensorReadingEntity;
import com.smarthome.history.repository.SensorReadingRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * =============================================================================
 * PersistenceService - maps processed events to rows and stores them
 * =============================================================================
 * Idempotency: {@code readingId} is the primary key, so a redelivered event
 * would collide on insert; the duplicate is caught and skipped rather than
 * dead-lettered, since the data is already durably stored.
 * =============================================================================
 */
@Service
public class PersistenceService {

    private static final Logger log = LoggerFactory.getLogger(PersistenceService.class);

    private final SensorReadingRepository repository;
    private final Counter readingsPersisted;

    public PersistenceService(SensorReadingRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.readingsPersisted = Counter.builder("history.readings.persisted")
                .description("Total readings persisted to TimescaleDB").register(meterRegistry);
    }

    public void persist(SensorDataEvent event) {
        SensorReading reading = event.getReading();
        if (reading == null) {
            log.warn("Discarding processed event with no reading: correlationId={}", event.getCorrelationId());
            return;
        }
        Instant time = reading.getTimestamp() != null ? reading.getTimestamp()
                : (event.getProcessedAt() != null ? event.getProcessedAt() : Instant.now());

        SensorReadingEntity entity = new SensorReadingEntity(
                time,
                reading.getReadingId(),
                reading.getSensorId(),
                reading.getSensorType() != null ? reading.getSensorType().name() : null,
                reading.getValue(),
                reading.getUnit(),
                reading.getLocation(),
                event.getCorrelationId(),
                event.isAnomaly(),
                event.getAnomalyDescription());

        repository.save(entity);
        readingsPersisted.increment();
        log.debug("Persisted reading: sensorId={}, readingId={}, time={}",
                reading.getSensorId(), reading.getReadingId(), time);
    }
}
