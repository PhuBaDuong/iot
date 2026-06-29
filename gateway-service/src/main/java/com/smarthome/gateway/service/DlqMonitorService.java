package com.smarthome.gateway.service;

import com.smarthome.common.constants.RabbitMQConstants;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * =============================================================================
 * DLQ Monitor Service
 * =============================================================================
 * Observes the depth of every dead-letter queue and:
 *   1. Registers a {@code rabbitmq.queue.depth} gauge per DLQ (scraped by
 *      Prometheus via /actuator/prometheus).
 *   2. Periodically logs the depth so operators see growth without a dashboard.
 *
 * A non-zero DLQ depth means messages are failing processing after retries and
 * should trigger an alert (see Prometheus alerting rules in the roadmap).
 * =============================================================================
 */
@Service
public class DlqMonitorService {

    private static final Logger log = LoggerFactory.getLogger(DlqMonitorService.class);

    private static final List<String> DLQ_NAMES = List.of(
            RabbitMQConstants.SENSOR_READINGS_DLQ,
            RabbitMQConstants.PROCESSED_DATA_DLQ,
            RabbitMQConstants.ALERTS_DLQ);

    private final AmqpAdmin amqpAdmin;

    public DlqMonitorService(AmqpAdmin amqpAdmin, MeterRegistry meterRegistry) {
        this.amqpAdmin = amqpAdmin;
        for (String dlq : DLQ_NAMES) {
            Gauge.builder("rabbitmq.queue.depth", () -> queueDepth(dlq))
                    .description("Number of messages currently in the queue")
                    .tag("queue", dlq)
                    .register(meterRegistry);
        }
    }

    /**
     * Returns the current message count for a queue, or 0 if the queue cannot
     * be inspected (e.g. broker temporarily unavailable).
     */
    private double queueDepth(String queueName) {
        try {
            QueueInformation info = amqpAdmin.getQueueInfo(queueName);
            return info != null ? info.getMessageCount() : 0d;
        } catch (Exception e) {
            log.debug("Unable to read depth for queue {}: {}", queueName, e.getMessage());
            return 0d;
        }
    }

    /** Logs DLQ depths every 60 seconds; warns when any DLQ is non-empty. */
    @Scheduled(fixedDelayString = "${gateway.dlq-monitor.interval:60000}")
    public void reportDlqDepths() {
        for (String dlq : DLQ_NAMES) {
            double depth = queueDepth(dlq);
            if (depth > 0) {
                log.warn("Dead-letter queue {} has {} message(s) awaiting inspection", dlq, (long) depth);
            } else {
                log.debug("Dead-letter queue {} is empty", dlq);
            }
        }
    }
}
