package com.smarthome.processing.config;

import com.smarthome.common.constants.RabbitMQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ Configuration for the Processing Service
 * 
 * Consumer Configuration Notes:
 * -----------------------------
 * This service acts as a message consumer. Key concepts:
 * 
 * 1. Competing Consumers Pattern:
 *    - Multiple instances of this service can consume from the same queue
 *    - RabbitMQ distributes messages round-robin among consumers
 *    - Each message is delivered to exactly ONE consumer
 *    - This enables horizontal scaling for high throughput
 * 
 * 2. Message Acknowledgment:
 *    - AUTO mode: Spring automatically acks after successful processing
 *    - MANUAL mode: Application explicitly acks (for guaranteed processing)
 *    - NONE mode: Fire-and-forget (not recommended for critical data)
 * 
 * 3. Prefetch (QoS):
 *    - Controls how many unacked messages a consumer can have
 *    - Lower prefetch = fairer distribution across consumers
 *    - Higher prefetch = better throughput, but less fair distribution
 * 
 * 4. Consumer Tags:
 *    - Each consumer gets a unique tag for identification
 *    - Useful for monitoring and debugging
 * 
 * Queue Declarations:
 * - We declare queues here to ensure they exist before consuming
 * - In production, queues are typically created by the data-ingestion-service
 * - Idempotent: declaring an existing queue with same config is safe
 */
@Configuration
public class RabbitMQConfig {

    /**
     * Queue for processed sensor data events.
     * This queue receives data that has been validated and enriched
     * by the data-ingestion-service.
     */
    @Bean
    public Queue processedDataQueue() {
        // Arguments MUST match the gateway's declaration of this queue.
        return QueueBuilder
                .durable(RabbitMQConstants.PROCESSED_DATA_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_PROCESSED_DATA_ROUTING_KEY)
                .build();
    }

    /**
     * Queue for alert events.
     * Alerts are generated when sensor readings exceed thresholds
     * or when anomalies are detected.
     * 
     * In production, consider:
     * - Priority queues for different alert severities
     * - Separate queues for different alert types (critical, warning, info)
     * - Dead letter queues for failed alert processing
     */
    @Bean
    public Queue alertsQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.ALERTS_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_ALERTS_ROUTING_KEY)
                .build();
    }

    // =========================================================================
    // DEAD LETTER TOPOLOGY (declared identically in every service)
    // =========================================================================

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(RabbitMQConstants.DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue processedDataDlq() {
        return QueueBuilder.durable(RabbitMQConstants.PROCESSED_DATA_DLQ).build();
    }

    @Bean
    public Queue alertsDlq() {
        return QueueBuilder.durable(RabbitMQConstants.ALERTS_DLQ).build();
    }

    @Bean
    public Binding processedDataDlqBinding(Queue processedDataDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(processedDataDlq).to(deadLetterExchange)
                .with(RabbitMQConstants.DLX_PROCESSED_DATA_ROUTING_KEY);
    }

    @Bean
    public Binding alertsDlqBinding(Queue alertsDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(alertsDlq).to(deadLetterExchange)
                .with(RabbitMQConstants.DLX_ALERTS_ROUTING_KEY);
    }

    /**
     * Route messages to the DLX once listener retries are exhausted instead of
     * requeueing them indefinitely.
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }

    /**
     * JSON Message Converter for serializing/deserializing messages.
     * Uses Jackson for JSON processing.
     * 
     * Important: Both producer and consumer must use the same
     * serialization format for messages to be correctly processed.
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate configured with JSON message converter.
     * Used for sending messages (though this service primarily consumes).
     * 
     * May be used for:
     * - Sending acknowledgment events
     * - Forwarding processed data to other services
     * - Publishing derived metrics
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}

