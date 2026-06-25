package com.smarthome.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * =============================================================================
 * Gateway Service - Entry Point for Sensor Data Processing
 * =============================================================================
 * 
 * LEARNING NOTE: Role of the Gateway Service
 * ------------------------------------------
 * In a microservices architecture, a Gateway Service acts as a central
 * entry point for data flowing into the system. This service:
 * 
 * 1. CONSUMES: Receives raw sensor readings from RabbitMQ
 * 2. VALIDATES: Ensures data integrity and format correctness
 * 3. DETECTS: Identifies anomalies based on configurable thresholds
 * 4. ROUTES: Publishes to appropriate downstream queues
 * 
 * Message Flow:
 * ┌─────────────┐     ┌─────────────┐     ┌─────────────────────┐
 * │   Sensor    │────▶│  RabbitMQ   │────▶│   Gateway Service   │
 * │  Simulator  │     │   Queue     │     │  (Validate/Detect)  │
 * └─────────────┘     └─────────────┘     └──────────┬──────────┘
 *                                                    │
 *                     ┌──────────────────────────────┼──────────────┐
 *                     │                              │              │
 *                     ▼                              ▼              ▼
 *              ┌─────────────┐              ┌─────────────┐  ┌─────────────┐
 *              │  Processed  │              │   Alerts    │  │   (Other    │
 *              │    Queue    │              │   Queue     │  │   Queues)   │
 *              └─────────────┘              └─────────────┘  └─────────────┘
 * 
 * =============================================================================
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class GatewayServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}

