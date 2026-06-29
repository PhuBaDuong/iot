package com.smarthome.sensor.service;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.HeartbeatEvent;
import com.smarthome.sensor.config.SensorConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * =============================================================================
 * HeartbeatPublisher - periodic device liveness signals (Phase 2)
 * =============================================================================
 * Publishes a {@link HeartbeatEvent} for every simulated sensor to the existing
 * {@code sensor.exchange} using the {@code device.heartbeat} routing key. The
 * Device Registry consumes these to refresh each device's lastSeenAt and keep
 * it ACTIVE; gaps cause the registry's inactivity sweep to mark it INACTIVE.
 * =============================================================================
 */
@Service
public class HeartbeatPublisher {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final SensorConfig sensorConfig;
    private final Counter heartbeatsPublished;

    public HeartbeatPublisher(RabbitTemplate rabbitTemplate, SensorConfig sensorConfig,
                              MeterRegistry meterRegistry) {
        this.rabbitTemplate = rabbitTemplate;
        this.sensorConfig = sensorConfig;
        this.heartbeatsPublished = Counter.builder("sensor.heartbeats.published")
                .description("Total device heartbeats published").register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${simulation.heartbeat.interval:15000}")
    public void publishHeartbeats() {
        Instant now = Instant.now();
        for (SensorConfig.SensorDefinition sensor : sensorConfig.getSensors()) {
            HeartbeatEvent heartbeat = new HeartbeatEvent(sensor.getId(), now);
            rabbitTemplate.convertAndSend(
                    RabbitMQConstants.SENSOR_EXCHANGE,
                    RabbitMQConstants.DEVICE_HEARTBEAT_ROUTING_KEY,
                    heartbeat);
            heartbeatsPublished.increment();
        }
        log.debug("Published {} heartbeats", sensorConfig.getSensors().size());
    }
}
