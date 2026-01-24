package com.smarthome.processing.model;

import com.smarthome.common.dto.SensorType;

import java.time.Instant;

/**
 * Sensor Statistics Model
 *
 * Maintains running statistics for a sensor's readings.
 * Uses incremental computation for efficiency - no need to
 * store all historical readings.
 *
 * Statistical Measures:
 * - Count: Total number of readings processed
 * - Sum: Running sum for average calculation
 * - Min/Max: Extreme values observed
 * - Average: Computed as sum/count
 *
 * Thread Safety:
 * This class is NOT thread-safe by itself. Thread safety is
 * managed at the service level using ConcurrentHashMap and
 * atomic operations.
 *
 * For production systems, consider:
 * - Time-windowed statistics (last hour, day, week)
 * - Percentiles (p50, p95, p99)
 * - Standard deviation
 * - Rate of change
 */
public class SensorStatistics {

    private String sensorId;
    private SensorType sensorType;
    private String location;

    // Running statistics
    private long count;
    private double sum;
    private double min;
    private double max;
    private double average;

    // Last update timestamp
    private Instant lastUpdated;

    public SensorStatistics() {
    }

    public SensorStatistics(String sensorId, SensorType sensorType, String location,
                           long count, double sum, double min, double max,
                           double average, Instant lastUpdated) {
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.location = location;
        this.count = count;
        this.sum = sum;
        this.min = min;
        this.max = max;
        this.average = average;
        this.lastUpdated = lastUpdated;
    }

    // Getters
    public String getSensorId() {
        return sensorId;
    }

    public SensorType getSensorType() {
        return sensorType;
    }

    public String getLocation() {
        return location;
    }

    public long getCount() {
        return count;
    }

    public double getSum() {
        return sum;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public double getAverage() {
        return average;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    // Setters
    public void setSensorId(String sensorId) {
        this.sensorId = sensorId;
    }

    public void setSensorType(SensorType sensorType) {
        this.sensorType = sensorType;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public void setCount(long count) {
        this.count = count;
    }

    public void setSum(double sum) {
        this.sum = sum;
    }

    public void setMin(double min) {
        this.min = min;
    }

    public void setMax(double max) {
        this.max = max;
    }

    public void setAverage(double average) {
        this.average = average;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    // Builder pattern
    public static SensorStatisticsBuilder builder() {
        return new SensorStatisticsBuilder();
    }

    public static class SensorStatisticsBuilder {
        private String sensorId;
        private SensorType sensorType;
        private String location;
        private long count;
        private double sum;
        private double min;
        private double max;
        private double average;
        private Instant lastUpdated;

        public SensorStatisticsBuilder sensorId(String sensorId) {
            this.sensorId = sensorId;
            return this;
        }

        public SensorStatisticsBuilder sensorType(SensorType sensorType) {
            this.sensorType = sensorType;
            return this;
        }

        public SensorStatisticsBuilder location(String location) {
            this.location = location;
            return this;
        }

        public SensorStatisticsBuilder count(long count) {
            this.count = count;
            return this;
        }

        public SensorStatisticsBuilder sum(double sum) {
            this.sum = sum;
            return this;
        }

        public SensorStatisticsBuilder min(double min) {
            this.min = min;
            return this;
        }

        public SensorStatisticsBuilder max(double max) {
            this.max = max;
            return this;
        }

        public SensorStatisticsBuilder average(double average) {
            this.average = average;
            return this;
        }

        public SensorStatisticsBuilder lastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
            return this;
        }

        public SensorStatistics build() {
            return new SensorStatistics(sensorId, sensorType, location, count, sum, min, max, average, lastUpdated);
        }
    }

    /**
     * Updates statistics with a new sensor reading.
     * Uses incremental calculation for efficiency.
     * 
     * @param value The new sensor reading value
     * @param timestamp The timestamp of the reading
     */
    public void updateWithReading(double value, Instant timestamp) {
        if (count == 0) {
            // First reading - initialize all values
            min = value;
            max = value;
            sum = value;
            count = 1;
        } else {
            // Incremental update
            min = Math.min(min, value);
            max = Math.max(max, value);
            sum += value;
            count++;
        }
        
        // Recalculate average
        average = sum / count;
        lastUpdated = timestamp;
    }

    /**
     * Creates a new SensorStatistics instance for a sensor.
     * 
     * @param sensorId The sensor identifier
     * @param sensorType The type of sensor
     * @param location The sensor location
     * @return A new SensorStatistics with zero counts
     */
    public static SensorStatistics create(String sensorId, SensorType sensorType, String location) {
        return SensorStatistics.builder()
                .sensorId(sensorId)
                .sensorType(sensorType)
                .location(location)
                .count(0)
                .sum(0.0)
                .min(Double.MAX_VALUE)
                .max(Double.MIN_VALUE)
                .average(0.0)
                .lastUpdated(Instant.now())
                .build();
    }

    /**
     * Resets all statistics to initial state.
     * Useful for time-windowed analytics.
     */
    public void reset() {
        count = 0;
        sum = 0.0;
        min = Double.MAX_VALUE;
        max = Double.MIN_VALUE;
        average = 0.0;
        lastUpdated = Instant.now();
    }
}

