package com.smarthome.sensor.service;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.dto.SensorType;
import com.smarthome.sensor.config.SensorConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * =============================================================================
 * Sensor Simulator Service - Core Simulation Logic
 * =============================================================================
 *
 * LEARNING NOTE: Message Publishing in RabbitMQ
 * ----------------------------------------------
 * This service demonstrates how to publish messages to RabbitMQ:
 *
 * 1. CREATE the message (SensorReading object)
 * 2. DETERMINE the routing key (how the exchange routes the message)
 * 3. PUBLISH using RabbitTemplate.convertAndSend()
 *
 * The RabbitTemplate handles:
 * - Connection management
 * - JSON serialization (via our MessageConverter)
 * - Error handling and retries
 *
 * =============================================================================
 */
@Service
public class SensorSimulatorService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SensorSimulatorService.class);

    private final RabbitTemplate rabbitTemplate;
    private final SensorConfig sensorConfig;

    public SensorSimulatorService(RabbitTemplate rabbitTemplate, SensorConfig sensorConfig) {
        this.rabbitTemplate = rabbitTemplate;
        this.sensorConfig = sensorConfig;
    }
    
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    // Track last values for realistic gradual changes
    private final Map<String, Double> lastValues = new HashMap<>();

    /**
     * Scheduled task that generates and publishes sensor readings.
     * 
     * LEARNING NOTE: @Scheduled with fixedDelayString
     * ------------------------------------------------
     * - fixedDelayString: Delay between end of last execution and start of next
     * - Uses SpEL (${...}) to read interval from configuration
     * - The scheduler runs this method in a dedicated thread pool
     */
    @Scheduled(fixedDelayString = "${simulation.interval:2000}")
    public void generateAndPublishReadings() {
        if (!running.get()) {
            return;
        }
        
        sensorConfig.getSensors().forEach(this::publishReading);
    }

    /**
     * Publishes a single sensor reading.
     * Can be called manually to trigger readings on demand.
     */
    public void publishReading(SensorConfig.SensorDefinition sensor) {
        SensorReading reading = generateReading(sensor);
        
        /*
         * LEARNING NOTE: Routing Key Construction
         * ----------------------------------------
         * The routing key determines how the Topic Exchange routes messages.
         * Format: sensor.{type}.{location}
         * 
         * Examples:
         * - sensor.temperature.living-room
         * - sensor.humidity.bathroom
         * 
         * Subscribers can use patterns:
         * - "sensor.#" = all sensors
         * - "sensor.temperature.*" = all temperature sensors
         * - "sensor.*.living-room" = all living room sensors
         */
        String routingKey = String.format(
            RabbitMQConstants.SENSOR_ROUTING_KEY_FORMAT,
            sensor.getType().getName(),
            sensor.getLocation()
        );
        
        /*
         * LEARNING NOTE: Publishing Messages
         * -----------------------------------
         * convertAndSend() does three things:
         * 1. Converts the SensorReading to JSON (via Jackson)
         * 2. Creates an AMQP message with the JSON payload
         * 3. Sends to the exchange with the routing key
         * 
         * Parameters:
         * - exchange: The exchange to publish to
         * - routingKey: Used by exchange to route the message
         * - message: The object to send (will be converted to JSON)
         */
        rabbitTemplate.convertAndSend(
            RabbitMQConstants.SENSOR_EXCHANGE,
            routingKey,
            reading
        );
        
        log.debug("Published reading: {} -> {} = {} {}", 
            reading.getSensorId(), 
            routingKey,
            reading.getValue(),
            reading.getUnit());
    }

    /**
     * Generates a realistic sensor reading with some randomness.
     */
    private SensorReading generateReading(SensorConfig.SensorDefinition sensor) {
        SensorType type = sensor.getType();
        double value = generateRealisticValue(sensor.getId(), type);
        
        return SensorReading.builder()
            .readingId(UUID.randomUUID().toString())
            .sensorId(sensor.getId())
            .sensorType(type)
            .value(value)
            .unit(type.getUnit())
            .location(sensor.getLocation())
            .timestamp(Instant.now())
            .metadata(createMetadata())
            .build();
    }

    /**
     * Generates a realistic value based on sensor type.
     * Values change gradually for realism, with occasional larger variations.
     */
    private double generateRealisticValue(String sensorId, SensorType type) {
        double lastValue = lastValues.getOrDefault(sensorId, getDefaultValue(type));
        double maxChange = (type.getMaxValue() - type.getMinValue()) * 0.05;
        double change = (random.nextDouble() - 0.5) * 2 * maxChange;
        double newValue = Math.max(type.getMinValue(), 
                         Math.min(type.getMaxValue(), lastValue + change));
        newValue = Math.round(newValue * 100.0) / 100.0;
        lastValues.put(sensorId, newValue);
        return newValue;
    }

    private double getDefaultValue(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> 22.0;
            case HUMIDITY -> 45.0;
            case MOTION -> 0.0;
            case LIGHT -> 500.0;
            case PRESSURE -> 1013.25;
        };
    }

    private Map<String, Object> createMetadata() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("firmwareVersion", "1.2.3");
        metadata.put("batteryLevel", 85 + random.nextInt(15));
        return metadata;
    }

    public boolean isRunning() { return running.get(); }
    public void start() { running.set(true); log.info("Simulation started"); }
    public void stop() { running.set(false); log.info("Simulation stopped"); }
}

