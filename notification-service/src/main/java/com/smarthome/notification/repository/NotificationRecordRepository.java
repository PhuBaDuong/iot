package com.smarthome.notification.repository;

import com.smarthome.notification.entity.NotificationRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface NotificationRecordRepository extends JpaRepository<NotificationRecord, Long> {
    List<NotificationRecord> findByAlertId(String alertId);
    List<NotificationRecord> findBySensorIdOrderBySentAtDesc(String sensorId, Pageable pageable);
    List<NotificationRecord> findBySentAtBetweenOrderBySentAtDesc(Instant from, Instant to, Pageable pageable);
    long countByAlertId(String alertId);
    // For dedup: check if an alert was already processed recently
    boolean existsByAlertIdAndStatusNot(String alertId, NotificationRecord.Status status);
}
