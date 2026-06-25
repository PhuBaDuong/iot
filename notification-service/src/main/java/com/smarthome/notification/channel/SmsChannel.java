package com.smarthome.notification.channel;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SmsChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(SmsChannel.class);

    @Override public NotificationRecord.Channel getChannel() { return NotificationRecord.Channel.SMS; }
    @Override public boolean isEnabled(NotificationPreference pref) { return pref.isSmsEnabled() && pref.getPhoneNumber() != null; }
    @Override public String getRecipient(NotificationPreference pref) { return pref.getPhoneNumber(); }

    @Override
    public NotificationRecord.Status send(AlertEvent alert, NotificationPreference pref) {
        // Stub: In production, integrate with Twilio/AWS SNS
        log.info("[SMS] Would send to {}: [{}] {}", pref.getPhoneNumber(), alert.getSeverity(), alert.getMessage());
        return NotificationRecord.Status.SENT;
    }
}
