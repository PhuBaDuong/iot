package com.smarthome.notification.channel;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class EmailChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    @Override public NotificationRecord.Channel getChannel() { return NotificationRecord.Channel.EMAIL; }
    @Override public boolean isEnabled(NotificationPreference pref) { return pref.isEmailEnabled() && pref.getEmailAddress() != null; }
    @Override public String getRecipient(NotificationPreference pref) { return pref.getEmailAddress(); }

    @Override
    public NotificationRecord.Status send(AlertEvent alert, NotificationPreference pref) {
        // Stub: In production, integrate with SendGrid/AWS SES
        log.info("[EMAIL] Would send to {}: [{}] {}", pref.getEmailAddress(), alert.getSeverity(), alert.getMessage());
        return NotificationRecord.Status.SENT;
    }
}
