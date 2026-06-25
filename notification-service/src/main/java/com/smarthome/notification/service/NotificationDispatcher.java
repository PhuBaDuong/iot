package com.smarthome.notification.service;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.channel.NotificationChannel;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import com.smarthome.notification.repository.NotificationRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final List<NotificationChannel> channels;
    private final NotificationRecordRepository recordRepository;
    private final Counter notificationsSent;
    private final Counter notificationsFailed;

    public NotificationDispatcher(List<NotificationChannel> channels,
                                   NotificationRecordRepository recordRepository,
                                   MeterRegistry meterRegistry) {
        this.channels = channels;
        this.recordRepository = recordRepository;
        this.notificationsSent = Counter.builder("notifications.sent")
                .description("Total notifications sent").register(meterRegistry);
        this.notificationsFailed = Counter.builder("notifications.failed")
                .description("Total notifications failed").register(meterRegistry);
    }

    public List<NotificationRecord> dispatch(AlertEvent alert, NotificationPreference preference) {
        List<NotificationRecord> records = new ArrayList<>();

        for (NotificationChannel channel : channels) {
            if (!channel.isEnabled(preference)) continue;

            NotificationRecord record = new NotificationRecord();
            record.setAlertId(alert.getAlertId());
            String sensorId = alert.getTriggeringReading() != null
                    ? alert.getTriggeringReading().getSensorId() : "unknown";
            record.setSensorId(sensorId);
            record.setChannel(channel.getChannel());
            record.setSeverity(alert.getSeverity().name());
            record.setMessage(alert.getMessage());
            record.setRecipient(channel.getRecipient(preference));
            record.setCorrelationId(alert.getCorrelationId());

            try {
                NotificationRecord.Status status = channel.send(alert, preference);
                record.setStatus(status);
                if (status == NotificationRecord.Status.SENT) {
                    notificationsSent.increment();
                }
            } catch (Exception e) {
                log.error("Failed to send via {}: {}", channel.getChannel(), e.getMessage());
                record.setStatus(NotificationRecord.Status.FAILED);
                record.setFailureReason(e.getMessage());
                notificationsFailed.increment();
            }

            record.setSentAt(Instant.now());
            records.add(recordRepository.save(record));
        }

        return records;
    }
}
