package com.smarthome.notification.service;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import com.smarthome.notification.repository.NotificationPreferenceRepository;
import com.smarthome.notification.repository.NotificationRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class AlertNotificationService {
    private static final Logger log = LoggerFactory.getLogger(AlertNotificationService.class);

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationRecordRepository recordRepository;
    private final NotificationDispatcher dispatcher;
    private final Counter alertsReceived;
    private final Counter alertsDeduplicated;

    @Value("${notification.dedup.window-seconds:300}")
    private int dedupWindowSeconds;

    public AlertNotificationService(NotificationPreferenceRepository preferenceRepository,
                                     NotificationRecordRepository recordRepository,
                                     NotificationDispatcher dispatcher,
                                     MeterRegistry meterRegistry) {
        this.preferenceRepository = preferenceRepository;
        this.recordRepository = recordRepository;
        this.dispatcher = dispatcher;
        this.alertsReceived = Counter.builder("notifications.alerts.received")
                .description("Total alerts received").register(meterRegistry);
        this.alertsDeduplicated = Counter.builder("notifications.alerts.deduplicated")
                .description("Total alerts suppressed by dedup").register(meterRegistry);
    }

    public List<NotificationRecord> processAlert(AlertEvent alert) {
        alertsReceived.increment();

        // Dedup check: skip if this alert was already processed
        if (isDuplicate(alert)) {
            log.info("Duplicate alert suppressed: alertId={}", alert.getAlertId());
            alertsDeduplicated.increment();
            return Collections.emptyList();
        }

        // For now, use a default preference set since we don't have user context from the alert.
        // In production, the alert could contain a device owner mapping.
        List<NotificationPreference> preferences = preferenceRepository.findAll();
        if (preferences.isEmpty()) {
            log.warn("No notification preferences configured; alert {} will not be dispatched", alert.getAlertId());
            return Collections.emptyList();
        }

        // Dispatch to all users whose min severity matches
        List<NotificationRecord> allRecords = new java.util.ArrayList<>();
        for (NotificationPreference pref : preferences) {
            if (severityMeetsThreshold(alert.getSeverity(), pref.getMinSeverity())) {
                allRecords.addAll(dispatcher.dispatch(alert, pref));
            }
        }
        return allRecords;
    }

    private boolean isDuplicate(AlertEvent alert) {
        return recordRepository.existsByAlertIdAndStatusNot(
                alert.getAlertId(), NotificationRecord.Status.FAILED);
    }

    private boolean severityMeetsThreshold(AlertEvent.Severity alertSeverity,
                                            NotificationPreference.MinSeverity minSeverity) {
        return alertSeverity.ordinal() >= minSeverity.ordinal();
    }
}
