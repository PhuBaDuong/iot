package com.smarthome.registry.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.HeartbeatEvent;
import com.smarthome.registry.service.DeviceService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * =============================================================================
 * HeartbeatListener - consumes device.heartbeat messages
 * =============================================================================
 * Bound to {@link RabbitMQConstants#DEVICE_HEARTBEAT_QUEUE}. Each heartbeat
 * refreshes the device's lastSeenAt and promotes it to ACTIVE. Failures are
 * dead-lettered via the queue's DLX configuration.
 * =============================================================================
 */
@Component
public class HeartbeatListener {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatListener.class);

    private final DeviceService deviceService;
    private final Counter heartbeatsReceived;

    public HeartbeatListener(DeviceService deviceService, MeterRegistry meterRegistry) {
        this.deviceService = deviceService;
        this.heartbeatsReceived = Counter.builder("registry.heartbeats.received")
                .description("Total device heartbeats consumed").register(meterRegistry);
    }

    @RabbitListener(queues = RabbitMQConstants.DEVICE_HEARTBEAT_QUEUE)
    public void handleHeartbeat(HeartbeatEvent event) {
        if (event == null || event.sensorId() == null || event.sensorId().isBlank()) {
            log.warn("Discarding heartbeat with missing sensorId");
            return;
        }
        log.debug("Heartbeat received: sensorId={}, timestamp={}", event.sensorId(), event.timestamp());
        deviceService.recordHeartbeat(event.sensorId(), event.timestamp());
        heartbeatsReceived.increment();
    }
}
