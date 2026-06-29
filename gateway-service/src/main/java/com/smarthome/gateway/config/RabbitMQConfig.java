package com.smarthome.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smarthome.common.constants.RabbitMQConstants;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
                .build();
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
        return template;
    }
}

