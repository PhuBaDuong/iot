package com.smarthome.processing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Processing Service Application
 * 
 * This microservice is responsible for:
 * 1. Consuming processed sensor data from the data-ingestion-service via RabbitMQ
 * 2. Performing real-time analytics and maintaining sensor statistics
 * 3. Handling alert events and managing notifications
 * 4. Exposing REST APIs for querying analytics data
 * 
 * Architecture Notes:
 * - This service acts as a downstream consumer in the event-driven architecture
 * - It maintains in-memory statistics for fast analytics queries
 * - Multiple instances can run for horizontal scaling (consumer groups)
 * - Each message is processed by exactly one consumer instance
 * 
 * Scaling Considerations:
 * - RabbitMQ's competing consumers pattern enables horizontal scaling
 * - Use prefetch settings to control message distribution
 * - Consider partitioning by sensor ID for stateful processing
 * 
 * @author SmartHome IoT Team
 */
@SpringBootApplication
public class ProcessingServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessingServiceApplication.class, args);
    }
}

