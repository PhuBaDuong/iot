package com.smarthome.processing.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.processing.service.AnalyticsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Processed Data Listener
 * 
 * Consumes sensor data events that have been processed and validated
 * by the data-ingestion-service.
 * 
 * Consumer Groups and Scaling:
 * ----------------------------
 * RabbitMQ uses the "competing consumers" pattern:
 * 
 * 1. Multiple Instances:
 *    - Deploy multiple instances of this service
 *    - All instances listen to the same queue
 *    - RabbitMQ distributes messages round-robin
 *    - Each message goes to exactly ONE consumer
 * 
 * 2. Prefetch Setting:
 *    - Controls how many messages are sent to a consumer before ack
 *    - Low prefetch (1-10): Better distribution, lower throughput
 *    - High prefetch (100+): Better throughput, potential hotspots
 *    - Configure in application.yml: spring.rabbitmq.listener.simple.prefetch
 * 
 * 3. Concurrency:
 *    - Each listener can have multiple concurrent consumers
 *    - Configure: spring.rabbitmq.listener.simple.concurrency
 *    - Threads within same instance share the AnalyticsService
 * 
 * Message Acknowledgment:
 * -----------------------
 * - AUTO mode (default): Message acked after listener method returns
 * - If exception thrown, message is rejected and requeued
 * - MANUAL mode: Explicit ack/nack for fine-grained control
 * 
 * Error Handling:
 * ---------------
 * - Failed messages are retried based on retry configuration
 * - After max retries, messages go to dead letter queue (if configured)
 * - Important: Ensure idempotent processing for retried messages
 */
@Component
public class ProcessedDataListener {

    private static final Logger log = LoggerFactory.getLogger(ProcessedDataListener.class);

    private final AnalyticsService analyticsService;
    private final Counter eventsProcessed;

    public ProcessedDataListener(AnalyticsService analyticsService, MeterRegistry meterRegistry) {
        this.analyticsService = analyticsService;
        this.eventsProcessed = Counter.builder("processing.events.processed")
                .description("Total processed-data events consumed").register(meterRegistry);
    }

    /**
     * Listens for processed sensor data events.
     * 
     * The @RabbitListener annotation:
     * - Automatically creates a consumer for the specified queue
     * - Handles message deserialization using configured MessageConverter
     * - Manages acknowledgments based on configuration
     * 
     * @param event The deserialized SensorDataEvent
     */
    @RabbitListener(queues = RabbitMQConstants.PROCESSED_DATA_QUEUE)
    public void handleProcessedData(SensorDataEvent event) {
        String sensorId = event.getReading() != null
                ? event.getReading().getSensorId() : "unknown";
        // Propagate the upstream correlationId into the MDC for structured logs.
        MDC.put("correlationId", event.getCorrelationId());

        log.debug("Received processed data event: correlationId={}, sensorId={}, isAnomaly={}",
                event.getCorrelationId(),
                sensorId,
                event.isAnomaly());

        try {
            // Update analytics with the new reading
            analyticsService.updateWithEvent(event);
            eventsProcessed.increment();

            log.debug("Successfully processed event: correlationId={}", event.getCorrelationId());

            // Message is automatically acknowledged on successful return
            // If we throw an exception, it will be nacked and requeued

        } catch (Exception e) {
            // Log the error - Spring will handle retry/DLQ based on config
            log.error("Error processing sensor data event: correlationId={}, error={}",
                    event.getCorrelationId(), e.getMessage(), e);

            // Re-throw to trigger retry mechanism
            throw e;
        } finally {
            MDC.remove("correlationId");
        }
    }
}

