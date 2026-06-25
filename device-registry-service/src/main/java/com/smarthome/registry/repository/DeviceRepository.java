package com.smarthome.registry.repository;

import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.registry.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/**
 * =============================================================================
 * DeviceRepository - Spring Data JPA access to the devices table
 * =============================================================================
 */
public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {

    /**
     * Devices in a given status whose last heartbeat predates {@code cutoff}.
     * Used by the inactivity sweep to find ACTIVE devices that have gone silent.
     */
    List<DeviceEntity> findByStatusAndLastSeenAtBefore(DeviceStatus status, Instant cutoff);
}
