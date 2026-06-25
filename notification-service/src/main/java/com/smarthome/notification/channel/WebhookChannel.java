package com.smarthome.notification.channel;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WebhookChannel implements NotificationChannel {
    private static final Logger log = LoggerFactory.getLogger(WebhookChannel.class);

    @Override public NotificationRecord.Channel getChannel() { return NotificationRecord.Channel.WEBHOOK; }
    @Override public boolean isEnabled(NotificationPreference pref) { return pref.isWebhookEnabled() && pref.getWebhookUrl() != null; }
    @Override public String getRecipient(NotificationPreference pref) { return pref.getWebhookUrl(); }

    @Override
    public NotificationRecord.Status send(AlertEvent alert, NotificationPreference pref) {
        // Stub: In production, POST JSON payload to the webhook URL
        log.info("[WEBHOOK] Would POST JSON to {}: [{}] {}", pref.getWebhookUrl(), alert.getSeverity(), alert.getMessage());
        return NotificationRecord.Status.SENT;
    }
}
