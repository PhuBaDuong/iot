package com.smarthome.history.config;

import com.smarthome.common.constants.RabbitMQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * RabbitMQ Configuration - telemetry persistence consumer topology
 * =============================================================================
 * Declares a dedicated persistence queue bound to {@code sensor.exchange} with
 * the same {@code data.processed} routing key the processing-service uses. The
 * topic exchange delivers each processed event to BOTH queues (fan-out), so the
 * history-service persists everything independently of analytics.
 * =============================================================================
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange sensorExchange() {
        return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
    }

    @Bean
    public Queue telemetryPersistenceQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.TELEMETRY_PERSISTENCE_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_TELEMETRY_PERSISTENCE_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding telemetryPersistenceBinding(Queue telemetryPersistenceQueue, TopicExchange sensorExchange) {
        return BindingBuilder.bind(telemetryPersistenceQueue).to(sensorExchange)
                .with(RabbitMQConstants.PROCESSED_ROUTING_KEY);
    }

    /** Direct dead-letter exchange (declared in every service for idempotent topology). */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(RabbitMQConstants.DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue telemetryPersistenceDlq() {
        return QueueBuilder.durable(RabbitMQConstants.TELEMETRY_PERSISTENCE_DLQ).build();
    }

    @Bean
    public Binding telemetryPersistenceDlqBinding(Queue telemetryPersistenceDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(telemetryPersistenceDlq).to(deadLetterExchange)
                .with(RabbitMQConstants.DLX_TELEMETRY_PERSISTENCE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
