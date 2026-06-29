package com.smarthome.registry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * =============================================================================
 * Device Registry Service (Phase 2)
 * =============================================================================
 * The authoritative store of device metadata and lifecycle state. It:
 *
 *   1. Exposes a REST API to register, query, update, and decommission devices.
 *   2. Consumes {@code device.heartbeat} messages from RabbitMQ and refreshes
 *      each device's {@code lastSeenAt}, promoting it to ACTIVE.
 *   3. Runs a scheduled inactivity sweep that flips silent devices to INACTIVE.
 *   4. Publishes {@code device.status.changed} to Redis pub/sub so the gateway
 *      can evict its device-status cache the instant a state changes.
 *
 * {@code @EnableScheduling} powers the inactivity sweep.
 * =============================================================================
 */
@SpringBootApplication
@EnableScheduling
public class DeviceRegistryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeviceRegistryServiceApplication.class, args);
    }
}
