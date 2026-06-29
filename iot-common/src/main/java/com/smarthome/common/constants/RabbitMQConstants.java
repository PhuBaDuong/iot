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

    // =========================================================================
    // DEVICE HEARTBEAT (Phase 2 - Device Registry)
    // =========================================================================
    // Devices publish lightweight heartbeats to the existing sensor.exchange
    // (topic) with the routing key below. The Device Registry binds its own
    // queue to consume them and refresh each device's lastSeenAt. The key does
    // NOT match the "sensor.#" pattern, so heartbeats never reach the gateway's
    // readings queue.

    /** Routing key for device heartbeats published to the sensor exchange */
    public static final String DEVICE_HEARTBEAT_ROUTING_KEY = "device.heartbeat";

    /** Queue (owned by the Device Registry) that receives device heartbeats */
    public static final String DEVICE_HEARTBEAT_QUEUE = "device.heartbeat.queue";

    // =========================================================================
    // TELEMETRY PERSISTENCE (Phase 2 - History Service)
    // =========================================================================
    // The history-service binds its OWN queue to the sensor exchange using the
    // same "data.processed" routing key as the processing-service. Because the
    // exchange is a topic exchange, every processed event is delivered to BOTH
    // queues: processing-service runs analytics while history-service durably
    // persists each reading to TimescaleDB. This is fan-out, NOT a competing
    // consumer on the analytics queue.

    /** Queue (owned by the History Service) that durably persists processed data */
    public static final String TELEMETRY_PERSISTENCE_QUEUE = "telemetry.persistence.queue";

    /** Dead-letter queue for telemetry persistence */
    public static final String TELEMETRY_PERSISTENCE_DLQ = "telemetry.persistence.dlq";

    /** DLX routing key for telemetry persistence */
    public static final String DLX_TELEMETRY_PERSISTENCE_ROUTING_KEY = "dlx.telemetry.persistence";

    // =========================================================================
    // DEAD LETTER TOPOLOGY
    // =========================================================================
    // A dead-letter exchange (DLX) receives messages that are rejected and not
    // requeued (e.g. after retries are exhausted) or that exceed their TTL.
    // We use a single direct DLX that routes to one dedicated DLQ per source
    // queue, so failures can be inspected and replayed independently.

    /** Direct exchange that collects dead-lettered messages */
    public static final String DLX_EXCHANGE = "dlx.exchange";

    /** Dead-letter queue for sensor readings */
    public static final String SENSOR_READINGS_DLQ = "sensor.readings.dlq";

    /** Dead-letter queue for processed data events */
    public static final String PROCESSED_DATA_DLQ = "processed.data.dlq";

    /** Dead-letter queue for alerts */
    public static final String ALERTS_DLQ = "alerts.dlq";

    /** Dead-letter queue for device heartbeats */
    public static final String DEVICE_HEARTBEAT_DLQ = "device.heartbeat.dlq";

    /** DLX routing key for sensor readings */
    public static final String DLX_SENSOR_READINGS_ROUTING_KEY = "dlx.sensor.readings";

    /** DLX routing key for processed data */
    public static final String DLX_PROCESSED_DATA_ROUTING_KEY = "dlx.processed.data";

    /** DLX routing key for alerts */
    public static final String DLX_ALERTS_ROUTING_KEY = "dlx.alerts";

    /** DLX routing key for device heartbeats */
    public static final String DLX_DEVICE_HEARTBEAT_ROUTING_KEY = "dlx.device.heartbeat";

    // =========================================================================
    // QUEUE ARGUMENT KEYS
    // =========================================================================

    /** Argument key declaring the dead-letter exchange for a queue */
    public static final String ARG_DEAD_LETTER_EXCHANGE = "x-dead-letter-exchange";

    /** Argument key declaring the dead-letter routing key for a queue */
    public static final String ARG_DEAD_LETTER_ROUTING_KEY = "x-dead-letter-routing-key";

    /** Argument key declaring a per-message TTL for a queue */
    public static final String ARG_MESSAGE_TTL = "x-message-ttl";

    /** Default message TTL for the sensor readings queue (5 minutes, in ms) */
    public static final int SENSOR_READINGS_TTL_MS = 300_000;
}

