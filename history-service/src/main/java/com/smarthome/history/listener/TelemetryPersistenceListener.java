package com.smarthome.history.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.history.service.PersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * =============================================================================
 * TelemetryPersistenceListener - durably stores every processed event
 * =============================================================================
 * Bound to {@link RabbitMQConstants#TELEMETRY_PERSISTENCE_QUEUE}, its own queue
 * fanned out from the topic exchange on the {@code data.processed} routing key.
 * A duplicate delivery (same readingId) is treated as already-persisted and
 * acknowledged; any other failure propagates so the message is dead-lettered.
 * =============================================================================
 */
@Component
public class TelemetryPersistenceListener {

    private static final Logger log = LoggerFactory.getLogger(TelemetryPersistenceListener.class);

    private final PersistenceService persistenceService;

    public TelemetryPersistenceListener(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    @RabbitListener(queues = RabbitMQConstants.TELEMETRY_PERSISTENCE_QUEUE)
    public void handleProcessedData(SensorDataEvent event) {
        MDC.put("correlationId", event.getCorrelationId());
        try {
            persistenceService.persist(event);
        } catch (DataIntegrityViolationException dup) {
            log.debug("Duplicate reading ignored: correlationId={}", event.getCorrelationId());
        } finally {
            MDC.remove("correlationId");
        }
    }
}
