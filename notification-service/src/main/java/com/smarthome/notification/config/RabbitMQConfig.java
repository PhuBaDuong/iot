package com.smarthome.notification.config;

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
 * =============================================================================
 * RabbitMQ Configuration for the Notification Service
 * =============================================================================
 * This service consumes alert events from the alerts queue and dispatches
 * notifications via configured channels (email, SMS, webhook, push).
 * =============================================================================
 */
@Configuration
public class RabbitMQConfig {

    /**
     * Queue for alert events.
     * Arguments MUST match the gateway's declaration of this queue.
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
    public Queue alertsDlq() {
        return QueueBuilder.durable(RabbitMQConstants.ALERTS_DLQ).build();
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
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * RabbitTemplate configured with JSON message converter.
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}
