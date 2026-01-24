package com.smarthome.sensor.config;

import com.smarthome.common.dto.SensorType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * =============================================================================
 * Sensor Configuration - External Configuration Binding
 * =============================================================================
 *
 * LEARNING NOTE: @ConfigurationProperties
 * ----------------------------------------
 * This annotation binds external configuration (application.yml) to this class.
 *
 * Benefits:
 * 1. Type-safe configuration (compile-time checking)
 * 2. IDE auto-completion for config properties
 * 3. Validation support with @Validated
 * 4. Easy testing - just create a SensorConfig object
 *
 * The "simulation" prefix means properties are read from:
 * simulation:
 *   interval: 2000
 *   sensors:
 *     - id: temp-001
 *       type: TEMPERATURE
 *       location: living-room
 *
 * =============================================================================
 */
@ConfigurationProperties(prefix = "simulation")
public class SensorConfig {

    /**
     * How often to generate sensor readings (in milliseconds).
     * Default: 2000ms (2 seconds)
     */
    private long interval = 2000;

    /**
     * List of sensor definitions to simulate.
     */
    private List<SensorDefinition> sensors = new ArrayList<>();

    public long getInterval() {
        return interval;
    }

    public void setInterval(long interval) {
        this.interval = interval;
    }

    public List<SensorDefinition> getSensors() {
        return sensors;
    }

    public void setSensors(List<SensorDefinition> sensors) {
        this.sensors = sensors;
    }

    /**
     * Represents a single sensor definition from configuration.
     *
     * LEARNING NOTE: Nested Configuration Classes
     * --------------------------------------------
     * Spring Boot can bind nested YAML structures to nested Java classes.
     * This keeps configuration organized and type-safe.
     */
    public static class SensorDefinition {
        /**
         * Unique identifier for the sensor.
         */
        private String id;

        /**
         * Type of sensor (TEMPERATURE, HUMIDITY, etc.)
         */
        private SensorType type;

        /**
         * Physical location of the sensor.
         */
        private String location;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public SensorType getType() {
            return type;
        }

        public void setType(SensorType type) {
            this.type = type;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }
}

