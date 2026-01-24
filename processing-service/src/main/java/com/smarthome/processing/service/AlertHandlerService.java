package com.smarthome.processing.service;

import com.smarthome.common.event.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Alert Handler Service
 * 
 * Processes and handles alert events from the message queue.
 * In a real production system, this would integrate with:
 * - Email notification services (SendGrid, AWS SES)
 * - SMS gateways (Twilio, AWS SNS)
 * - Push notification services (Firebase, OneSignal)
 * - Incident management systems (PagerDuty, OpsGenie)
 * - Slack/Teams webhooks for team notifications
 * 
 * Alert Severity Levels:
 * - CRITICAL: Immediate action required (e.g., fire detection, security breach)
 * - HIGH: Urgent attention needed (e.g., equipment failure)
 * - MEDIUM: Should be addressed soon (e.g., threshold exceeded)
 * - LOW: Informational alerts (e.g., maintenance reminders)
 * 
 * Alert Processing Patterns:
 * 1. Deduplication: Avoid sending duplicate alerts for same issue
 * 2. Rate limiting: Don't overwhelm users with too many alerts
 * 3. Escalation: Auto-escalate if alert not acknowledged
 * 4. Aggregation: Group related alerts together
 */
@Service
public class AlertHandlerService {

    private static final Logger log = LoggerFactory.getLogger(AlertHandlerService.class);

    // In-memory storage for recent alerts (bounded)
    private final ConcurrentLinkedDeque<AlertEvent> recentAlerts = new ConcurrentLinkedDeque<>();
    
    @Value("${analytics.max-recent-alerts:1000}")
    private int maxRecentAlerts;

    /**
     * Processes an incoming alert event.
     *
     * @param alertEvent The alert event to process
     */
    public void handleAlert(AlertEvent alertEvent) {
        // Log with appropriate severity
        logAlert(alertEvent);

        // Store in recent alerts
        storeAlert(alertEvent);

        // In a real system, this would trigger notifications:
        // sendEmailNotification(alertEvent);
        // sendSmsNotification(alertEvent);
        // sendPushNotification(alertEvent);
        // createIncidentTicket(alertEvent);

        String sensorId = alertEvent.getTriggeringReading() != null
                ? alertEvent.getTriggeringReading().getSensorId() : "unknown";
        log.info("Alert processed: sensorId={}, severity={}, message={}",
                sensorId,
                alertEvent.getSeverity(),
                alertEvent.getMessage());
    }

    /**
     * Logs the alert with appropriate log level based on severity.
     */
    private void logAlert(AlertEvent alertEvent) {
        String sensorId = alertEvent.getTriggeringReading() != null
                ? alertEvent.getTriggeringReading().getSensorId() : "unknown";
        String sensorType = alertEvent.getTriggeringReading() != null
                ? alertEvent.getTriggeringReading().getSensorType().name() : "unknown";
        Double actualValue = alertEvent.getActualValue() != null
                ? alertEvent.getActualValue() : 0.0;
        Double threshold = alertEvent.getThreshold() != null
                ? alertEvent.getThreshold() : 0.0;

        String alertLog = String.format(
                "[ALERT] Sensor: %s | Type: %s | Severity: %s | Message: %s | Value: %.2f (Threshold: %.2f)",
                sensorId,
                sensorType,
                alertEvent.getSeverity(),
                alertEvent.getMessage(),
                actualValue,
                threshold
        );

        switch (alertEvent.getSeverity()) {
            case CRITICAL:
                log.error("🚨 CRITICAL ALERT: {}", alertLog);
                break;
            case WARNING:
                log.warn("⚠️ WARNING ALERT: {}", alertLog);
                break;
            case INFO:
            default:
                log.info("ℹ️ INFO ALERT: {}", alertLog);
                break;
        }
    }

    /**
     * Stores the alert in the recent alerts deque.
     */
    private void storeAlert(AlertEvent alertEvent) {
        recentAlerts.addFirst(alertEvent);
        
        // Evict old alerts if over limit
        while (recentAlerts.size() > maxRecentAlerts) {
            recentAlerts.pollLast();
        }
    }

    /**
     * Gets all recent alerts.
     */
    public List<AlertEvent> getRecentAlerts() {
        return new ArrayList<>(recentAlerts);
    }

    /**
     * Gets recent alerts limited to a specific count.
     */
    public List<AlertEvent> getRecentAlerts(int limit) {
        List<AlertEvent> alerts = new ArrayList<>();
        int count = 0;
        for (AlertEvent alert : recentAlerts) {
            if (count >= limit) break;
            alerts.add(alert);
            count++;
        }
        return alerts;
    }

    /**
     * Gets the count of recent alerts.
     */
    public int getAlertCount() {
        return recentAlerts.size();
    }

    /**
     * Clears all stored alerts.
     * Useful for testing or reset scenarios.
     */
    public void clearAlerts() {
        recentAlerts.clear();
        log.info("All alerts cleared");
    }
}

