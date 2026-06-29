package com.smarthome.sensor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * =============================================================================
 * Sensor Simulator Application - Entry Point
 * =============================================================================
 * 
 * LEARNING NOTE: Spring Boot Annotations
 * ---------------------------------------
 * @SpringBootApplication combines three annotations:
 * - @Configuration: Marks this as a configuration class
 * - @EnableAutoConfiguration: Auto-configures beans based on classpath
 * - @ComponentScan: Scans for @Component classes in this package and below
 * 
 * @EnableScheduling enables Spring's scheduled task execution capability.
 * This allows us to use @Scheduled annotations to run tasks at fixed intervals.
 * The scheduler creates a thread pool to execute scheduled tasks.
 * 
 * @ConfigurationPropertiesScan scans for @ConfigurationProperties classes.
 * This is needed for our SensorConfig class to be automatically registered.
 * 
 * =============================================================================
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SensorSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SensorSimulatorApplication.class, args);
    }
}

