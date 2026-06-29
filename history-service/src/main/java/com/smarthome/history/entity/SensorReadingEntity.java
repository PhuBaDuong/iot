package com.smarthome.history.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * =============================================================================
 * SensorReadingEntity - JPA mapping for the sensor_readings hypertable
 * =============================================================================
 * The table (and its TimescaleDB hypertable + compression) is created by the
 * DB init scripts, so JPA runs with {@code ddl-auto: none}.
 *
 * The natural identity is {@code readingId} (a per-reading UUID). Implementing
 * {@link Persistable} and always reporting {@code isNew() == true} forces
 * Hibernate to INSERT without a prior SELECT, which matters because the
 * hypertable has no index on reading_id and the workload is append-only.
 * =============================================================================
 */
@Entity
@Table(name = "sensor_readings")
public class SensorReadingEntity implements Persistable<String> {

    @Column(name = "time", nullable = false)
    private Instant time;

    @Id
    @Column(name = "reading_id")
    private String readingId;

    @Column(name = "sensor_id", nullable = false)
    private String sensorId;

    @Column(name = "sensor_type", nullable = false)
    private String sensorType;

    @Column(name = "value")
    private Double value;

    @Column(name = "unit")
    private String unit;

    @Column(name = "location")
    private String location;

    @Column(name = "correlation_id")
    private String correlationId;

    @Column(name = "anomaly", nullable = false)
    private boolean anomaly;

    @Column(name = "anomaly_description")
    private String anomalyDescription;

    protected SensorReadingEntity() {
    }

    public SensorReadingEntity(Instant time, String readingId, String sensorId, String sensorType,
                               Double value, String unit, String location, String correlationId,
                               boolean anomaly, String anomalyDescription) {
        this.time = time;
        this.readingId = readingId;
        this.sensorId = sensorId;
        this.sensorType = sensorType;
        this.value = value;
        this.unit = unit;
        this.location = location;
        this.correlationId = correlationId;
        this.anomaly = anomaly;
        this.anomalyDescription = anomalyDescription;
    }

    @Override
    @JsonIgnore
    public String getId() {
        return readingId;
    }

    @Override
    @JsonIgnore
    @Transient
    public boolean isNew() {
        return true;
    }

    public Instant getTime() { return time; }
    public String getReadingId() { return readingId; }
    public String getSensorId() { return sensorId; }
    public String getSensorType() { return sensorType; }
    public Double getValue() { return value; }
    public String getUnit() { return unit; }
    public String getLocation() { return location; }
    public String getCorrelationId() { return correlationId; }
    public boolean isAnomaly() { return anomaly; }
    public String getAnomalyDescription() { return anomalyDescription; }
}
