package com.smarthome.registry.service;

import com.smarthome.common.constants.RedisConstants;
import com.smarthome.common.dto.DeviceDto;
import com.smarthome.common.dto.DeviceRegistrationRequest;
import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.registry.entity.DeviceEntity;
import com.smarthome.registry.exception.DeviceNotFoundException;
import com.smarthome.registry.repository.DeviceRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * =============================================================================
 * DeviceService - device lifecycle and the source of truth for device state
 * =============================================================================
 * Registration is an idempotent upsert (the simulator re-registers on every
 * startup). Any status transition is persisted and announced on Redis pub/sub
 * via {@link RedisConstants#DEVICE_STATUS_CHANGED_CHANNEL} so the gateway can
 * evict its cached view immediately.
 * =============================================================================
 */
@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository repository;
    private final StringRedisTemplate redisTemplate;
    private final Counter statusChanges;

    public DeviceService(DeviceRepository repository, StringRedisTemplate redisTemplate,
                         MeterRegistry meterRegistry) {
        this.repository = repository;
        this.redisTemplate = redisTemplate;
        this.statusChanges = Counter.builder("registry.device.status_changes")
                .description("Total device status transitions").register(meterRegistry);
    }

    /** Idempotent upsert. New devices start PROVISIONED; existing metadata is refreshed. */
    @Transactional
    public DeviceDto register(DeviceRegistrationRequest request) {
        DeviceEntity device = repository.findById(request.sensorId()).orElse(null);
        if (device == null) {
            device = new DeviceEntity(request.sensorId(), request.name(), request.sensorType(),
                    request.location(), request.firmwareVersion(), DeviceStatus.PROVISIONED,
                    request.owner(), Instant.now(), null);
            log.info("Registered new device: sensorId={}, type={}", request.sensorId(), request.sensorType());
        } else {
            device.setName(request.name());
            device.setSensorType(request.sensorType());
            device.setLocation(request.location());
            device.setFirmwareVersion(request.firmwareVersion());
            device.setOwner(request.owner());
            log.debug("Re-registered existing device: sensorId={}", request.sensorId());
        }
        return toDto(repository.save(device));
    }

    @Transactional(readOnly = true)
    public DeviceDto getDevice(String sensorId) {
        return toDto(require(sensorId));
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> listDevices() {
        return repository.findAll().stream().map(DeviceService::toDto).toList();
    }

    /** Admin-driven status change. Rejects transitions away from DECOMMISSIONED. */
    @Transactional
    public DeviceDto updateStatus(String sensorId, DeviceStatus newStatus) {
        DeviceEntity device = require(sensorId);
        if (device.getStatus() == DeviceStatus.DECOMMISSIONED) {
            throw new IllegalStateException("Device is decommissioned and cannot change status: " + sensorId);
        }
        applyStatus(device, newStatus);
        return toDto(repository.save(device));
    }

    /** Records a heartbeat: refreshes lastSeenAt and promotes the device to ACTIVE. */
    @Transactional
    public void recordHeartbeat(String sensorId, Instant timestamp) {
        DeviceEntity device = repository.findById(sensorId).orElse(null);
        if (device == null) {
            log.warn("Heartbeat for unknown device ignored: sensorId={}", sensorId);
            return;
        }
        device.setLastSeenAt(timestamp != null ? timestamp : Instant.now());
        if (device.getStatus() == DeviceStatus.PROVISIONED || device.getStatus() == DeviceStatus.INACTIVE) {
            applyStatus(device, DeviceStatus.ACTIVE);
        }
        repository.save(device);
    }

    @Transactional
    public DeviceDto decommission(String sensorId) {
        DeviceEntity device = require(sensorId);
        applyStatus(device, DeviceStatus.DECOMMISSIONED);
        return toDto(repository.save(device));
    }

    @Transactional
    public void delete(String sensorId) {
        if (!repository.existsById(sensorId)) {
            throw new DeviceNotFoundException(sensorId);
        }
        repository.deleteById(sensorId);
        log.info("Deleted device: sensorId={}", sensorId);
    }

    /** Flips ACTIVE devices silent past {@code cutoff} to INACTIVE; returns the count. */
    @Transactional
    public int sweepInactive(Instant cutoff) {
        List<DeviceEntity> stale = repository.findByStatusAndLastSeenAtBefore(DeviceStatus.ACTIVE, cutoff);
        for (DeviceEntity device : stale) {
            applyStatus(device, DeviceStatus.INACTIVE);
            repository.save(device);
        }
        return stale.size();
    }

    private DeviceEntity require(String sensorId) {
        return repository.findById(sensorId).orElseThrow(() -> new DeviceNotFoundException(sensorId));
    }

    /** Applies a status change and, when it actually changes, announces it on Redis. */
    private void applyStatus(DeviceEntity device, DeviceStatus newStatus) {
        if (device.getStatus() == newStatus) {
            return;
        }
        log.info("Device status change: sensorId={}, {} -> {}",
                device.getSensorId(), device.getStatus(), newStatus);
        device.setStatus(newStatus);
        statusChanges.increment();
        publishStatusChanged(device.getSensorId());
    }

    private void publishStatusChanged(String sensorId) {
        try {
            redisTemplate.convertAndSend(RedisConstants.DEVICE_STATUS_CHANGED_CHANNEL, sensorId);
        } catch (RuntimeException e) {
            log.warn("Failed to publish device.status.changed for {}: {}", sensorId, e.getMessage());
        }
    }

    private static DeviceDto toDto(DeviceEntity d) {
        return new DeviceDto(d.getSensorId(), d.getName(), d.getSensorType(), d.getLocation(),
                d.getFirmwareVersion(), d.getStatus(), d.getOwner(), d.getProvisionedAt(), d.getLastSeenAt());
    }
}
