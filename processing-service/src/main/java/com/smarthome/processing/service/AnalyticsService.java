package com.smarthome.processing.service;

import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.processing.model.SensorStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Analytics Service
 * 
 * Performs real-time analytics on sensor data streams.
 * Maintains in-memory statistics and recent readings for fast queries.
 * 
 * Analytics Patterns Used:
 * 1. Incremental Statistics: Updates stats with each reading (O(1) per update)
 * 2. Sliding Window: Keeps last N readings for trend analysis
 * 3. Per-Sensor Aggregation: Separate stats per sensor for granular insights
 * 
 * Thread Safety:
 * - Uses ConcurrentHashMap for thread-safe sensor statistics storage
 * - Uses ConcurrentLinkedDeque for thread-safe recent readings
 * - Atomic operations via compute/computeIfAbsent for updates
 * 
 * Scaling Considerations:
 * - In-memory storage limits horizontal scaling (state is not shared)
 * - For multi-instance deployments, consider:
 *   - Redis for shared state
 *   - Kafka Streams for stateful stream processing
 *   - Apache Flink for complex event processing
 * 
 * Memory Management:
 * - Recent readings are bounded by maxRecentReadings
 * - Old readings are automatically evicted
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    // Thread-safe storage for sensor statistics
    private final ConcurrentHashMap<String, SensorStatistics> sensorStats = new ConcurrentHashMap<>();
    
    // Thread-safe storage for recent readings per sensor
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<SensorReading>> recentReadings = new ConcurrentHashMap<>();
    
    @Value("${analytics.max-recent-readings:100}")
    private int maxRecentReadings;

    /**
     * Updates analytics with a new sensor data event.
     * Called by the ProcessedDataListener for each consumed message.
     * 
     * @param event The sensor data event to process
     */
    public void updateWithEvent(SensorDataEvent event) {
        SensorReading reading = event.getReading();
        String sensorId = reading.getSensorId();
        
        // Update statistics using atomic compute operation
        sensorStats.compute(sensorId, (key, existingStats) -> {
            SensorStatistics stats = existingStats != null 
                    ? existingStats 
                    : SensorStatistics.create(sensorId, reading.getSensorType(), reading.getLocation());
            
            stats.updateWithReading(reading.getValue(), reading.getTimestamp());
            return stats;
        });
        
        // Store recent reading
        storeRecentReading(sensorId, reading);
        
        log.debug("Updated analytics for sensor {}: count={}, avg={}", 
                sensorId, 
                sensorStats.get(sensorId).getCount(),
                String.format("%.2f", sensorStats.get(sensorId).getAverage()));
    }

    /**
     * Stores a recent reading, maintaining a bounded deque.
     */
    private void storeRecentReading(String sensorId, SensorReading reading) {
        recentReadings.computeIfAbsent(sensorId, k -> new ConcurrentLinkedDeque<>())
                .addLast(reading);
        
        // Evict old readings if over limit
        ConcurrentLinkedDeque<SensorReading> readings = recentReadings.get(sensorId);
        while (readings.size() > maxRecentReadings) {
            readings.pollFirst();
        }
    }

    /**
     * Gets statistics for a specific sensor.
     */
    public Optional<SensorStatistics> getStatistics(String sensorId) {
        return Optional.ofNullable(sensorStats.get(sensorId));
    }

    /**
     * Gets statistics for all sensors.
     */
    public Collection<SensorStatistics> getAllStatistics() {
        return new ArrayList<>(sensorStats.values());
    }

    /**
     * Gets recent readings for a specific sensor.
     */
    public List<SensorReading> getRecentReadings(String sensorId) {
        ConcurrentLinkedDeque<SensorReading> readings = recentReadings.get(sensorId);
        return readings != null ? new ArrayList<>(readings) : Collections.emptyList();
    }

    /**
     * Gets the total number of sensors being tracked.
     */
    public int getSensorCount() {
        return sensorStats.size();
    }

    /**
     * Gets the total number of readings processed.
     */
    public long getTotalReadingsProcessed() {
        return sensorStats.values().stream()
                .mapToLong(SensorStatistics::getCount)
                .sum();
    }
}

