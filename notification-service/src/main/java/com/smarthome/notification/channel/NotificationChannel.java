package com.smarthome.notification.channel;

import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;

public interface NotificationChannel {
    NotificationRecord.Channel getChannel();
    boolean isEnabled(NotificationPreference preference);
    String getRecipient(NotificationPreference preference);
    NotificationRecord.Status send(AlertEvent alert, NotificationPreference preference);
}
