package com.smarthome.registry.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * =============================================================================
 * HeartbeatMonitor - scheduled inactivity sweep
 * =============================================================================
 * Periodically flips ACTIVE devices that have not been heard from within the
 * configured silence window to INACTIVE. The actual transition (and its Redis
 * announcement) is delegated to {@link DeviceService#sweepInactive(Instant)}.
 * =============================================================================
 */
@Component
public class HeartbeatMonitor {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final DeviceService deviceService;
    private final Duration inactiveAfter;

    public HeartbeatMonitor(DeviceService deviceService,
                            @Value("${registry.heartbeat.inactive-after:5m}") Duration inactiveAfter) {
        this.deviceService = deviceService;
        this.inactiveAfter = inactiveAfter;
    }

    @Scheduled(fixedDelayString = "${registry.heartbeat.sweep-interval:60000}")
    public void sweep() {
        Instant cutoff = Instant.now().minus(inactiveAfter);
        int flipped = deviceService.sweepInactive(cutoff);
        if (flipped > 0) {
            log.info("Inactivity sweep marked {} device(s) INACTIVE (silent before {})", flipped, cutoff);
        }
    }
}
