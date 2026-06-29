package com.smarthome.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * =============================================================================
 * NotificationRecord - audit log for every notification attempt
 * =============================================================================
 * Captures the channel, delivery status, severity, and recipient for each
 * notification sent (or suppressed). Indexed by alertId, sensorId, and
 * timestamp for efficient querying.
 * =============================================================================
 */
@Entity
@Table(name = "notification_records", indexes = {
        @Index(name = "idx_record_alert_id", columnList = "alert_id"),
        @Index(name = "idx_record_sensor_id", columnList = "sensor_id"),
        @Index(name = "idx_record_timestamp", columnList = "sent_at")
})
public class NotificationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "alert_id", nullable = false)
    private String alertId;

    @Column(name = "sensor_id", nullable = false)
    private String sensorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String severity;

    @Column(length = 1000)
    private String message;

    @Column
    private String recipient;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column(name = "correlation_id")
    private String correlationId;

    public enum Channel { EMAIL, SMS, WEBHOOK, PUSH }
    public enum Status { SENT, FAILED, SUPPRESSED }

    public NotificationRecord() {
        // for JPA
    }

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = Instant.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAlertId() { return alertId; }
    public void setAlertId(String alertId) { this.alertId = alertId; }

    public String getSensorId() { return sensorId; }
    public void setSensorId(String sensorId) { this.sensorId = sensorId; }

    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
}
