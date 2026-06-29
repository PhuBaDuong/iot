package com.smarthome.gateway.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.event.AlertEvent;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.gateway.service.AnomalyDetectionService;
import com.smarthome.gateway.service.AnomalyDetectionService.AnomalyDetails;
import com.smarthome.gateway.service.DeduplicationService;
import com.smarthome.gateway.service.DeviceAvailability;
import com.smarthome.gateway.service.DeviceRegistryClient;
import com.smarthome.gateway.service.OutboundPublisher;
import com.smarthome.gateway.service.RateLimitingService;
import com.smarthome.gateway.service.ValidationService;
import com.smarthome.gateway.service.ValidationService.ValidationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
    private final DeduplicationService deduplicationService;
    private final RateLimitingService rateLimitingService;
    private final DeviceRegistryClient deviceRegistryClient;
    private final OutboundPublisher outboundPublisher;

    // Micrometer counters (exported via /actuator/prometheus)
    private final Counter messagesReceived;
    private final Counter messagesProcessed;
    private final Counter validationFailures;
    private final Counter anomaliesDetected;
    private final Counter unknownDeviceRejections;

    public SensorDataListener(ValidationService validationService,
                              AnomalyDetectionService anomalyDetectionService,
                              DeduplicationService deduplicationService,
                              RateLimitingService rateLimitingService,
                              DeviceRegistryClient deviceRegistryClient,
                              OutboundPublisher outboundPublisher,
                              MeterRegistry meterRegistry) {
        this.validationService = validationService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.deduplicationService = deduplicationService;
        this.rateLimitingService = rateLimitingService;
        this.deviceRegistryClient = deviceRegistryClient;
        this.outboundPublisher = outboundPublisher;
        this.messagesReceived = Counter.builder("gateway.messages.received")
                .description("Total sensor readings received by the gateway").register(meterRegistry);
        this.messagesProcessed = Counter.builder("gateway.messages.processed")
                .description("Total sensor readings successfully processed").register(meterRegistry);
        this.validationFailures = Counter.builder("gateway.validation.failures")
                .description("Total sensor readings that failed validation").register(meterRegistry);
        this.anomaliesDetected = Counter.builder("gateway.anomalies.detected")
                .description("Total anomalies detected by the gateway").register(meterRegistry);
        this.unknownDeviceRejections = Counter.builder("gateway.device.rejected")
                .description("Total readings rejected from unknown/decommissioned devices")
                .register(meterRegistry);
    }

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
        messagesReceived.increment();
        String correlationId = UUID.randomUUID().toString();
        // Bind the correlationId to the MDC so every log line for this reading
        // (in this and called methods) carries it as a structured field. The
        // try/finally guarantees the thread-local is cleared for the next message.
        MDC.put("correlationId", correlationId);
        try {
            process(reading, correlationId);
        } finally {
            MDC.remove("correlationId");
        }
    }

    /**
     * Runs the full processing pipeline for a single reading. Extracted from the
     * listener method so the correlationId MDC context can be managed around it.
     */
    private void process(SensorReading reading, String correlationId) {

        log.debug("Received sensor reading: {} [correlationId={}]",
                reading.getReadingId(), correlationId);

        // Step 0a: Drop duplicate deliveries (idempotent processing).
        // Duplicates are discarded silently - they are not failures, so they
        // must NOT be dead-lettered.
        if (!deduplicationService.isFirstSeen(reading.getReadingId())) {
            return;
        }

        // Step 0b: Enforce the per-device rate limit. Over-limit readings are
        // rejected and routed to the dead-letter queue for later inspection.
        if (!rateLimitingService.isAllowed(reading.getSensorId())) {
            throw new AmqpRejectAndDontRequeueException(
                    "Rate limit exceeded for sensor " + reading.getSensorId());
        }

        // Step 0c: Validate the source device against the Device Registry
        // (cached 60s). Readings from unknown or decommissioned devices are
        // rejected to the DLQ; if the registry is unreachable we fail open.
        DeviceAvailability availability = deviceRegistryClient.getAvailability(reading.getSensorId());
        if (!availability.isAccepted()) {
            unknownDeviceRejections.increment();
            throw new AmqpRejectAndDontRequeueException(
                    "Reading rejected for sensor " + reading.getSensorId() + ": device " + availability);
        }

        // Step 1: Validate the incoming reading
        ValidationResult validationResult = validationService.validate(reading);
        
        // Step 2: Create the enriched event
        SensorDataEvent event = createSensorDataEvent(reading, validationResult, correlationId);

        if (!validationResult.valid()) {
            // Invalid readings are still published but marked as invalid
            validationFailures.increment();
            log.warn("Invalid reading rejected: {} - {}",
                    reading.getReadingId(), validationResult.getErrorMessage());
            publishProcessedData(event);
            return;
        }

        // Step 3: Check for anomalies
        Optional<AnomalyDetails> anomalyOpt = anomalyDetectionService.detectAnomaly(reading);
        
        if (anomalyOpt.isPresent()) {
            AnomalyDetails anomaly = anomalyOpt.get();
            anomaliesDetected.increment();

            // Update event with anomaly information
            event.setAnomaly(true);
            event.setAnomalyDescription(anomaly.description());
            
            // Publish alert for anomaly
            publishAlert(reading, anomaly, correlationId);
        }

        // Step 4: Publish to processed data queue
        publishProcessedData(event);
        messagesProcessed.increment();
        
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
        outboundPublisher.publishProcessedData(event);
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

        outboundPublisher.publishAlert(alert);
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
        return (long) messagesReceived.count();
    }

    public long getMessagesProcessed() {
        return (long) messagesProcessed.count();
    }

    public long getValidationFailures() {
        return (long) validationFailures.count();
    }

    public long getAnomaliesDetected() {
        return (long) anomaliesDetected.count();
    }
}

