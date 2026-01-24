package com.smarthome.gateway.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.event.AlertEvent;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.gateway.service.AnomalyDetectionService;
import com.smarthome.gateway.service.AnomalyDetectionService.AnomalyDetails;
import com.smarthome.gateway.service.ValidationService;
import com.smarthome.gateway.service.ValidationService.ValidationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * =============================================================================
 * Sensor Data Listener - RabbitMQ Message Consumer
 * =============================================================================
 * 
 * LEARNING NOTE: @RabbitListener Deep Dive
 * -----------------------------------------
 * The @RabbitListener annotation creates a message consumer that:
 * 
 * 1. SUBSCRIBES to the specified queue(s)
 * 2. DESERIALIZES messages using the configured MessageConverter
 * 3. INVOKES the annotated method with the deserialized object
 * 4. ACKNOWLEDGES the message after successful processing
 * 
 * MESSAGE ACKNOWLEDGMENT PATTERNS:
 * --------------------------------
 * - AUTO_ACKNOWLEDGE (default in Spring): 
 *   Message is acknowledged when listener method completes normally.
 *   If an exception is thrown, message is rejected/requeued.
 * 
 * - MANUAL_ACKNOWLEDGE:
 *   You explicitly call channel.basicAck() or channel.basicNack().
 *   Use when you need fine-grained control over acknowledgment.
 * 
 * - NONE:
 *   No acknowledgment. Message is removed immediately upon delivery.
 *   WARNING: Messages can be lost if processing fails!
 * 
 * ERROR HANDLING:
 * ---------------
 * When an exception is thrown:
 * 1. Message is rejected (basicNack or basicReject)
 * 2. By default, message is requeued for retry
 * 3. After max retries, message goes to Dead Letter Queue (if configured)
 * 
 * CONCURRENCY:
 * ------------
 * Use @RabbitListener(queues = "...", concurrency = "3-10") to control
 * how many concurrent consumers process messages in parallel.
 * 
 * =============================================================================
 */
@Component
public class SensorDataListener {

    private static final Logger log = LoggerFactory.getLogger(SensorDataListener.class);
    private static final String SERVICE_NAME = "gateway-service";

    private final ValidationService validationService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final RabbitTemplate rabbitTemplate;

    public SensorDataListener(ValidationService validationService,
                              AnomalyDetectionService anomalyDetectionService,
                              RabbitTemplate rabbitTemplate) {
        this.validationService = validationService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.rabbitTemplate = rabbitTemplate;
    }

    // Statistics counters
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong validationFailures = new AtomicLong(0);
    private final AtomicLong anomaliesDetected = new AtomicLong(0);

    /**
     * Consumes sensor readings from the SENSOR_READINGS_QUEUE.
     * 
     * LEARNING NOTE: Message Processing Flow
     * --------------------------------------
     * 1. Message arrives in queue
     * 2. Spring deserializes JSON to SensorReading object
     * 3. This method is invoked with the object
     * 4. We validate, detect anomalies, and route
     * 5. If no exception, message is acknowledged (removed from queue)
     * 
     * @param reading The deserialized sensor reading
     */
    @RabbitListener(queues = RabbitMQConstants.SENSOR_READINGS_QUEUE)
    public void handleSensorReading(SensorReading reading) {
        messagesReceived.incrementAndGet();
        String correlationId = UUID.randomUUID().toString();
        
        log.debug("Received sensor reading: {} [correlationId={}]", 
                reading.getReadingId(), correlationId);

        // Step 1: Validate the incoming reading
        ValidationResult validationResult = validationService.validate(reading);
        
        // Step 2: Create the enriched event
        SensorDataEvent event = createSensorDataEvent(reading, validationResult, correlationId);

        if (!validationResult.valid()) {
            // Invalid readings are still published but marked as invalid
            validationFailures.incrementAndGet();
            log.warn("Invalid reading rejected: {} - {}", 
                    reading.getReadingId(), validationResult.getErrorMessage());
            publishProcessedData(event);
            return;
        }

        // Step 3: Check for anomalies
        Optional<AnomalyDetails> anomalyOpt = anomalyDetectionService.detectAnomaly(reading);
        
        if (anomalyOpt.isPresent()) {
            AnomalyDetails anomaly = anomalyOpt.get();
            anomaliesDetected.incrementAndGet();
            
            // Update event with anomaly information
            event.setAnomaly(true);
            event.setAnomalyDescription(anomaly.description());
            
            // Publish alert for anomaly
            publishAlert(reading, anomaly, correlationId);
        }

        // Step 4: Publish to processed data queue
        publishProcessedData(event);
        messagesProcessed.incrementAndGet();
        
        log.debug("Successfully processed reading: {} [anomaly={}]", 
                reading.getReadingId(), event.isAnomaly());
    }

    /**
     * Create a SensorDataEvent with processing metadata.
     */
    private SensorDataEvent createSensorDataEvent(SensorReading reading,
                                                   ValidationResult validationResult,
                                                   String correlationId) {
        return SensorDataEvent.builder()
                .reading(reading)
                .processedAt(Instant.now())
                .processedBy(SERVICE_NAME)
                .valid(validationResult.valid())
                .validationError(validationResult.valid() ? null : validationResult.getErrorMessage())
                .anomaly(false)
                .anomalyDescription(null)
                .correlationId(correlationId)
                .build();
    }

    /**
     * Publish processed data to the PROCESSED_DATA_QUEUE.
     */
    private void publishProcessedData(SensorDataEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMQConstants.SENSOR_EXCHANGE,
                RabbitMQConstants.PROCESSED_ROUTING_KEY,
                event
        );
    }

    /**
     * Publish an alert to the ALERTS_QUEUE.
     */
    private void publishAlert(SensorReading reading, 
                              AnomalyDetails anomaly, 
                              String correlationId) {
        AlertEvent alert = AlertEvent.builder()
                .alertId(UUID.randomUUID().toString())
                .triggeringReading(reading)
                .severity(mapSeverity(anomaly.severity()))
                .message(anomaly.description())
                .timestamp(Instant.now())
                .threshold(anomaly.threshold())
                .actualValue(anomaly.actualValue())
                .correlationId(correlationId)
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConstants.ALERTS_EXCHANGE,
                RabbitMQConstants.ALERT_ROUTING_KEY,
                alert
        );

        log.info("Alert published: {} - {}", alert.getAlertId(), alert.getMessage());
    }

    /**
     * Map internal severity to AlertEvent severity.
     */
    private AlertEvent.Severity mapSeverity(AnomalyDetectionService.Severity severity) {
        return switch (severity) {
            case INFO -> AlertEvent.Severity.INFO;
            case WARNING -> AlertEvent.Severity.WARNING;
            case CRITICAL -> AlertEvent.Severity.CRITICAL;
        };
    }

    // =========================================================================
    // STATISTICS GETTERS (for controller)
    // =========================================================================

    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    public long getValidationFailures() {
        return validationFailures.get();
    }

    public long getAnomaliesDetected() {
        return anomaliesDetected.get();
    }
}

