package com.smarthome.common.constants;

/**
 * =============================================================================
 * RabbitMQ Constants - Central Configuration for Message Queue
 * =============================================================================
 * 
 * LEARNING NOTE: Why centralize queue/exchange names?
 * --------------------------------------------------
 * 1. Single source of truth - change once, applies everywhere
 * 2. Prevents typos that cause messages to go to wrong queues
 * 3. Makes it easy to understand the messaging topology
 * 
 * RABBITMQ CONCEPTS:
 * ------------------
 * - EXCHANGE: A router that receives messages and routes them to queues
 *   - Direct Exchange: Routes based on exact routing key match
 *   - Topic Exchange: Routes based on pattern matching (we use this!)
 *   - Fanout Exchange: Broadcasts to all bound queues
 * 
 * - QUEUE: A buffer that stores messages until consumed
 * 
 * - ROUTING KEY: A label attached to messages for routing decisions
 * 
 * - BINDING: A link between an exchange and a queue with a routing pattern
 * 
 * =============================================================================
 */
public final class RabbitMQConstants {

    private RabbitMQConstants() {
        // Prevent instantiation
    }

    // =========================================================================
    // EXCHANGES
    // =========================================================================
    // We use a Topic Exchange for flexible routing based on patterns
    // Pattern: sensor.{sensorType}.{location}
    // Examples: sensor.temperature.living-room, sensor.humidity.bedroom
    
    public static final String SENSOR_EXCHANGE = "sensor.exchange";
    public static final String ALERTS_EXCHANGE = "alerts.exchange";

    // =========================================================================
    // QUEUES
    // =========================================================================
    
    /** Raw sensor readings from all sensors */
    public static final String SENSOR_READINGS_QUEUE = "sensor.readings.queue";
    
    /** Validated and enriched sensor data ready for processing */
    public static final String PROCESSED_DATA_QUEUE = "processed.data.queue";
    
    /** Alert notifications for anomalies */
    public static final String ALERTS_QUEUE = "alerts.queue";

    // =========================================================================
    // ROUTING KEYS
    // =========================================================================
    // Topic exchange patterns:
    // * (star) matches exactly one word
    // # (hash) matches zero or more words
    
    /** Matches all sensor readings: sensor.temperature.*, sensor.humidity.*, etc. */
    public static final String SENSOR_ROUTING_KEY_PATTERN = "sensor.#";
    
    /** Specific routing key format for sensor readings */
    public static final String SENSOR_ROUTING_KEY_FORMAT = "sensor.%s.%s"; // sensor.{type}.{location}
    
    /** Routing key for processed data */
    public static final String PROCESSED_ROUTING_KEY = "data.processed";
    
    /** Routing key for alerts */
    public static final String ALERT_ROUTING_KEY = "alert.anomaly";
}

