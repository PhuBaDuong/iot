package com.smarthome.registry.entity;

import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.common.dto.SensorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * =============================================================================
 * DeviceEntity - JPA mapping for a registered device
 * =============================================================================
 * Persisted in the relational "devices" database. The business identifier
 * {@code sensorId} (the same id carried on every SensorReading) is the primary
 * key, so registration is a natural idempotent upsert.
 * =============================================================================
 */
@Entity
@Table(name = "devices")
public class DeviceEntity {

    @Id
    @Column(name = "sensor_id", nullable = false, updatable = false)
    private String sensorId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "sensor_type", nullable = false)
    private SensorType sensorType;

    @Column(nullable = false)
    private String location;

    @Column(name = "firmware_version")
    private String firmwareVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeviceStatus status;

    private String owner;

    @Column(name = "provisioned_at", nullable = false, updatable = false)
    private Instant provisionedAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected DeviceEntity() {
    }

    public DeviceEntity(String sensorId, String name, SensorType sensorType, String location,
                        String firmwareVersion, DeviceStatus status, String owner,
                        Instant provisionedAt, Instant lastSeenAt) {
        this.sensorId = sensorId;
        this.name = name;
        this.sensorType = sensorType;
        this.location = location;
        this.firmwareVersion = firmwareVersion;
        this.status = status;
        this.owner = owner;
        this.provisionedAt = provisionedAt;
        this.lastSeenAt = lastSeenAt;
    }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public SensorType getSensorType() { return sensorType; }
    public void setSensorType(SensorType sensorType) { this.sensorType = sensorType; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public DeviceStatus getStatus() { return status; }
    public void setStatus(DeviceStatus status) { this.status = status; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Instant getProvisionedAt() { return provisionedAt; }
    public void setProvisionedAt(Instant provisionedAt) { this.provisionedAt = provisionedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
