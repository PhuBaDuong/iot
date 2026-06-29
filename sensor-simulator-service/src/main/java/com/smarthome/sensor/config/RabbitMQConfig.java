package com.smarthome.sensor.config;

import com.smarthome.common.constants.RabbitMQConstants;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * =============================================================================
 * RabbitMQ Configuration - Message Infrastructure Setup
 * =============================================================================
 * 
 * LEARNING NOTE: RabbitMQ Core Concepts
 * --------------------------------------
 * 
 * 1. EXCHANGE: A message router that receives messages from producers
 *    - Producers never send directly to queues; they send to exchanges
 *    - The exchange decides which queue(s) should receive the message
 *    - Types: Direct, Topic, Fanout, Headers
 * 
 * 2. TOPIC EXCHANGE (what we use):
 *    - Routes messages based on routing key patterns
 *    - Patterns use wildcards:
 *      * (star) = matches exactly one word
 *      # (hash) = matches zero or more words
 *    - Example: "sensor.temperature.living-room" matches "sensor.#"
 * 
 * 3. QUEUE: A buffer that stores messages
 *    - Messages wait in queues until a consumer processes them
 *    - Queues can be durable (survive broker restart) or transient
 * 
 * 4. BINDING: A link between an exchange and a queue
 *    - Tells the exchange which queues to route messages to
 *    - Includes the routing key pattern for topic exchanges
 * 
 * 5. MESSAGE FLOW:
 *    Producer -> Exchange -> [Binding rules] -> Queue -> Consumer
 * 
 * =============================================================================
 */
@Configuration
public class RabbitMQConfig {

    /**
     * Creates a Topic Exchange for sensor data.
     * 
     * LEARNING NOTE: Why Topic Exchange?
     * -----------------------------------
     * Topic exchanges allow flexible routing based on patterns.
     * We can route messages like:
     * - "sensor.temperature.living-room" -> to temperature subscribers
     * - "sensor.*.bedroom" -> to all bedroom sensor subscribers
     * - "sensor.#" -> to subscribers wanting all sensor data
     */
    @Bean
    public TopicExchange sensorExchange() {
        // Parameters: name, durable (survives restart), auto-delete (deleted when unused)
        return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
    }

    /**
     * Creates the queue for sensor readings.
     * 
     * LEARNING NOTE: Durable Queues
     * -----------------------------
     * Durable queues survive broker restarts. This is important for:
     * - Not losing messages during maintenance
     * - Reliability in production environments
     */
    @Bean
    public Queue sensorReadingsQueue() {
        // Durable queue that survives RabbitMQ restart
        return new Queue(RabbitMQConstants.SENSOR_READINGS_QUEUE, true);
    }

    /**
     * Binds the sensor readings queue to the sensor exchange.
     * 
     * LEARNING NOTE: Binding with Routing Key
     * ----------------------------------------
     * The routing key pattern "sensor.#" means this queue receives
     * ALL messages with routing keys starting with "sensor."
     * 
     * Examples of matching routing keys:
     * - sensor.temperature.living-room ✓
     * - sensor.humidity.bathroom ✓
     * - alert.temperature.high ✗ (doesn't start with "sensor.")
     */
    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue, TopicExchange sensorExchange) {
        return BindingBuilder
                .bind(sensorReadingsQueue)
                .to(sensorExchange)
                .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN);
    }

    /**
     * Configures JSON message conversion.
     * 
     * LEARNING NOTE: Message Serialization
     * ------------------------------------
     * RabbitMQ messages are byte arrays. We need to convert our Java
     * objects to/from bytes. Jackson2JsonMessageConverter:
     * - Serializes objects to JSON when sending
     * - Deserializes JSON to objects when receiving
     * - Includes type information for polymorphic deserialization
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Configures the RabbitTemplate with our message converter.
     * 
     * LEARNING NOTE: RabbitTemplate
     * -----------------------------
     * RabbitTemplate is the main class for sending messages. It's similar
     * to JdbcTemplate or RestTemplate - a helper that simplifies operations.
     * 
     * Key methods:
     * - convertAndSend(): Converts object to message and sends
     * - convertSendAndReceive(): Send and wait for reply (RPC pattern)
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, 
                                          MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}

