package com.smarthome.history;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * =============================================================================
 * History / Persistence Service (Phase 2)
 * =============================================================================
 * Durable long-term store for telemetry. It binds its own queue to the
 * {@code data.processed} routing key (fan-out alongside processing-service),
 * writes every reading to the TimescaleDB {@code sensor_readings} hypertable,
 * and serves historical queries over {@code /api/history}.
 * =============================================================================
 */
@SpringBootApplication
public class HistoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HistoryServiceApplication.class, args);
    }
}
