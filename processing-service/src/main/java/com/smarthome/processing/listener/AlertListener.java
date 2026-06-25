package com.smarthome.processing.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.AlertEvent;
import com.smarthome.processing.service.AlertHandlerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Alert Listener
 * 
 * Consumes alert events from the alerts queue.
 * Alerts are generated when sensor readings exceed defined thresholds
 * or when anomalies are detected in the data.
 * 
 * Priority Queues and Alert Handling:
 * -----------------------------------
 * In production systems, consider implementing priority-based processing:
 * 
 * 1. Priority Queues:
 *    - RabbitMQ supports message priorities (0-255)
 *    - Higher priority messages are delivered first
 *    - Useful for CRITICAL alerts that need immediate attention
 *    - Configure: x-max-priority argument when declaring queue
 * 
 * 2. Separate Queues by Severity:
 *    - alerts.critical -> dedicated high-priority consumer
 *    - alerts.high -> standard priority
 *    - alerts.low -> batch processing
 *    - Enables different SLAs per severity level
 * 
 * 3. Rate Limiting:
 *    - Implement rate limiting to prevent alert storms
 *    - Use sliding window counters per sensor/type
 *    - Aggregate similar alerts within time windows
 * 
 * 4. Alert Deduplication:
 *    - Track recently processed alerts
 *    - Avoid sending duplicate notifications
 *    - Use alert fingerprint (sensor + type + threshold)
 * 
 * Best Practices for Alert Handling:
 * -----------------------------------
 * - Never drop critical alerts - ensure reliable delivery
 * - Implement circuit breakers for external notification services
 * - Log all alerts for audit trail
 * - Track alert metrics (count, latency, failures)
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final AlertHandlerService alertHandlerService;
    private final Counter alertsHandled;

    public AlertListener(AlertHandlerService alertHandlerService, MeterRegistry meterRegistry) {
        this.alertHandlerService = alertHandlerService;
        this.alertsHandled = Counter.builder("processing.alerts.handled")
                .description("Total alerts handled").register(meterRegistry);
    }

    /**
     * Listens for alert events from the alerts queue.
     * 
     * Processing Flow:
     * 1. Receive alert from queue
     * 2. Log alert details
     * 3. Delegate to AlertHandlerService for processing
     * 4. AlertHandlerService handles notifications
     * 
     * Error Handling:
     * - Failed alerts are retried based on configuration
     * - Critical alerts should have minimal retry delay
     * - Consider dead letter queue for failed alerts
     * 
     * @param alertEvent The deserialized AlertEvent
     */
    @RabbitListener(queues = RabbitMQConstants.ALERTS_QUEUE)
    public void handleAlert(AlertEvent alertEvent) {
        String sensorId = alertEvent.getTriggeringReading() != null
                ? alertEvent.getTriggeringReading().getSensorId() : "unknown";
        // Propagate the upstream correlationId into the MDC for structured logs.
        MDC.put("correlationId", alertEvent.getCorrelationId());

        log.info("Received alert: alertId={}, sensorId={}, severity={}",
                alertEvent.getAlertId(),
                sensorId,
                alertEvent.getSeverity());

        try {
            // Delegate to alert handler service
            alertHandlerService.handleAlert(alertEvent);
            alertsHandled.increment();

            log.debug("Alert processed successfully: {}", alertEvent.getAlertId());

        } catch (Exception e) {
            // Log error with full context
            log.error("Error processing alert: alertId={}, sensorId={}, severity={}, error={}",
                    alertEvent.getAlertId(),
                    sensorId,
                    alertEvent.getSeverity(),
                    e.getMessage(), e);

            // For critical alerts, you might want to:
            // 1. Send to a fallback notification system
            // 2. Write to a local file for later processing
            // 3. Trigger an emergency protocol

            // Re-throw to trigger retry
            throw e;
        } finally {
            MDC.remove("correlationId");
        }
    }
}

