package com.smarthome.registry.config;

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
 * RabbitMQ Configuration - device heartbeat consumer topology
 * =============================================================================
 * Declares the same {@code sensor.exchange} topic exchange (idempotent) and a
 * dedicated heartbeat queue bound with the {@code device.heartbeat} routing key
 * (which deliberately does NOT match the gateway's {@code sensor.#} pattern).
 * Failed heartbeats are dead-lettered to a per-queue DLQ.
 * =============================================================================
 */
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange sensorExchange() {
        return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
    }

    @Bean
    public Queue deviceHeartbeatQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.DEVICE_HEARTBEAT_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_DEVICE_HEARTBEAT_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding deviceHeartbeatBinding(Queue deviceHeartbeatQueue, TopicExchange sensorExchange) {
        return BindingBuilder.bind(deviceHeartbeatQueue).to(sensorExchange)
                .with(RabbitMQConstants.DEVICE_HEARTBEAT_ROUTING_KEY);
    }

    /** Direct dead-letter exchange (declared in every service for idempotent topology). */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder.directExchange(RabbitMQConstants.DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue deviceHeartbeatDlq() {
        return QueueBuilder.durable(RabbitMQConstants.DEVICE_HEARTBEAT_DLQ).build();
    }

    @Bean
    public Binding deviceHeartbeatDlqBinding(Queue deviceHeartbeatDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deviceHeartbeatDlq).to(deadLetterExchange)
                .with(RabbitMQConstants.DLX_DEVICE_HEARTBEAT_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
