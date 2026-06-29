package com.smarthome.gateway.service;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.AlertEvent;
import com.smarthome.common.event.SensorDataEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * =============================================================================
 * Outbound Publisher (Resilience4j-guarded)
 * =============================================================================
 * Centralizes every outbound publish so the calls can be wrapped with a
 * {@link CircuitBreaker} and {@link Retry}. This is a separate bean (rather than
 * private methods on the listener) so the Resilience4j AOP proxy can intercept
 * the calls - self-invocation inside a single bean is not proxied.
 *
 * Today the only outbound dependency is RabbitMQ; in Phase 2 the gateway will
 * also call the Device Registry over REST, and those calls will reuse the same
 * {@code rabbitPublisher}/named instances configured in application.yml.
 * =============================================================================
 */
@Service
public class OutboundPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboundPublisher.class);
    private static final String INSTANCE = "rabbitPublisher";

    private final RabbitTemplate rabbitTemplate;

    public OutboundPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** Publishes an enriched event to the processed-data queue. */
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public void publishProcessedData(SensorDataEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMQConstants.SENSOR_EXCHANGE,
                RabbitMQConstants.PROCESSED_ROUTING_KEY,
                event);
    }

    /** Publishes an alert to the alerts queue. */
    @CircuitBreaker(name = INSTANCE)
    @Retry(name = INSTANCE)
    public void publishAlert(AlertEvent alert) {
        rabbitTemplate.convertAndSend(
                RabbitMQConstants.ALERTS_EXCHANGE,
                RabbitMQConstants.ALERT_ROUTING_KEY,
                alert);
        log.info("Alert published: {} - {}", alert.getAlertId(), alert.getMessage());
    }
}
