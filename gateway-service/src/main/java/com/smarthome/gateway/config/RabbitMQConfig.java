package com.smarthome.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarthome.common.constants.RabbitMQConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
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
 * RabbitMQ Configuration for Gateway Service
 * =============================================================================
 * 
 * LEARNING NOTE: Message Consumption Patterns
 * -------------------------------------------
 * This configuration sets up the RabbitMQ infrastructure for consuming and
 * publishing messages. Key concepts:
 * 
 * 1. EXCHANGES: Message routers that receive messages and route them to queues
 *    - Topic Exchange: Routes based on routing key patterns (*.temperature.*)
 *    - We use topic exchanges for flexible sensor type routing
 * 
 * 2. QUEUES: Buffers that store messages until a consumer processes them
 *    - Durable: Survives broker restart
 *    - Exclusive: Only one consumer can connect (we don't use this)
 * 
 * 3. BINDINGS: Links between exchanges and queues with routing patterns
 *    - Defines which messages go to which queues
 * 
 * 4. MESSAGE ACKNOWLEDGMENT PATTERNS:
 *    - AUTO: Message removed from queue when delivered (risky!)
 *    - MANUAL: Consumer explicitly acknowledges after processing (safer)
 *    - Spring AMQP uses AUTO by default but handles exceptions properly
 * 
 * =============================================================================
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    // =========================================================================
    // EXCHANGES
    // =========================================================================
    
    /**
     * Sensor Exchange - receives all raw sensor readings.
     * 
     * LEARNING NOTE: Why Topic Exchange?
     * Topic exchanges allow pattern-based routing:
     * - "sensor.temperature.*" matches all temperature sensors
     * - "sensor.*.living-room" matches all sensors in living room
     * - "sensor.#" matches everything (# = zero or more words)
     */
    @Bean
    public TopicExchange sensorExchange() {
        return ExchangeBuilder
                .topicExchange(RabbitMQConstants.SENSOR_EXCHANGE)
                .durable(true)
                .build();
    }

    /**
     * Alerts Exchange - receives anomaly alerts for notification processing.
     */
    @Bean
    public TopicExchange alertsExchange() {
        return ExchangeBuilder
                .topicExchange(RabbitMQConstants.ALERTS_EXCHANGE)
                .durable(true)
                .build();
    }

    // =========================================================================
    // QUEUES
    // =========================================================================

    /**
     * Queue for raw sensor readings from simulators.
     * 
     * LEARNING NOTE: Queue Durability
     * Durable queues survive broker restarts. Messages in durable queues
     * with persistent delivery mode will not be lost on restart.
     */
    @Bean
    public Queue sensorReadingsQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.SENSOR_READINGS_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_SENSOR_READINGS_ROUTING_KEY)
                .withArgument(RabbitMQConstants.ARG_MESSAGE_TTL, RabbitMQConstants.SENSOR_READINGS_TTL_MS)
                .build();
    }

    /**
     * Queue for validated and processed sensor data.
     * Downstream services consume from this queue.
     */
    @Bean
    public Queue processedDataQueue() {
        return QueueBuilder
                .durable(RabbitMQConstants.PROCESSED_DATA_QUEUE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_EXCHANGE, RabbitMQConstants.DLX_EXCHANGE)
                .withArgument(RabbitMQConstants.ARG_DEAD_LETTER_ROUTING_KEY,
                        RabbitMQConstants.DLX_PROCESSED_DATA_ROUTING_KEY)
                .build();
    }

    /**
     * Queue for anomaly alerts.
     * Alert notification service consumes from this queue.
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
    // DEAD LETTER TOPOLOGY
    // =========================================================================

    /** Direct exchange that collects all dead-lettered messages. */
    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(RabbitMQConstants.DLX_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    public Queue sensorReadingsDlq() {
        return QueueBuilder.durable(RabbitMQConstants.SENSOR_READINGS_DLQ).build();
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
    public Binding sensorReadingsDlqBinding(Queue sensorReadingsDlq, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(sensorReadingsDlq).to(deadLetterExchange)
                .with(RabbitMQConstants.DLX_SENSOR_READINGS_ROUTING_KEY);
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
     * After listener retries are exhausted, reject the message without requeueing
     * so it is routed to the dead-letter exchange instead of looping forever.
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }

    // =========================================================================
    // BINDINGS
    // =========================================================================

    /**
     * Bind sensor readings queue to sensor exchange.
     * Uses wildcard pattern to receive all sensor messages.
     */
    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue, TopicExchange sensorExchange) {
        return BindingBuilder
                .bind(sensorReadingsQueue)
                .to(sensorExchange)
                .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN);
    }

    /**
     * Bind processed data queue to sensor exchange.
     */
    @Bean
    public Binding processedDataBinding(Queue processedDataQueue, TopicExchange sensorExchange) {
        return BindingBuilder
                .bind(processedDataQueue)
                .to(sensorExchange)
                .with(RabbitMQConstants.PROCESSED_ROUTING_KEY);
    }

    /**
     * Bind alerts queue to alerts exchange.
     */
    @Bean
    public Binding alertsBinding(Queue alertsQueue, TopicExchange alertsExchange) {
        return BindingBuilder
                .bind(alertsQueue)
                .to(alertsExchange)
                .with(RabbitMQConstants.ALERT_ROUTING_KEY);
    }

    // =========================================================================
    // MESSAGE CONVERTER
    // =========================================================================

    /**
     * JSON message converter using Jackson.
     *
     * LEARNING NOTE: Message Serialization
     * Messages in RabbitMQ are byte arrays. We use Jackson to:
     * 1. Serialize Java objects to JSON when publishing
     * 2. Deserialize JSON to Java objects when consuming
     *
     * JavaTimeModule is required for Java 8+ date/time types (Instant, etc.)
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    // =========================================================================
    // RABBIT TEMPLATE
    // =========================================================================

    /**
     * RabbitTemplate for publishing messages.
     *
     * LEARNING NOTE: RabbitTemplate
     * RabbitTemplate is the Spring helper for sending messages to RabbitMQ.
     * It handles:
     * 1. Connection management (via ConnectionFactory)
     * 2. Message conversion (via MessageConverter)
     * 3. Retry logic (configured in application.yml)
     * 4. Error handling
     *
     * Usage example:
     *   rabbitTemplate.convertAndSend(exchange, routingKey, message);
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);

        // Publisher confirms: broker acknowledges (or nacks) that it accepted the message.
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("Publisher confirm NACK [correlation={}]: {}",
                        correlation != null ? correlation.getId() : "n/a", cause);
            }
        });

        // Publisher returns: broker returns messages that could not be routed to any queue.
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.error(
                "Unroutable message returned: exchange={}, routingKey={}, replyCode={}, replyText={}",
                returned.getExchange(), returned.getRoutingKey(),
                returned.getReplyCode(), returned.getReplyText()));

        return template;
    }
}

