package com.smarthome.notification.listener;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.event.AlertEvent;
import com.smarthome.notification.service.AlertNotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AlertNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(AlertNotificationListener.class);

    private final AlertNotificationService notificationService;
    private final Counter alertsHandled;

    public AlertNotificationListener(AlertNotificationService notificationService,
                                      MeterRegistry meterRegistry) {
        this.notificationService = notificationService;
        this.alertsHandled = Counter.builder("notification.alerts.handled")
                .description("Total alerts handled by notification service")
                .register(meterRegistry);
    }

    @RabbitListener(queues = RabbitMQConstants.ALERTS_QUEUE)
    public void handleAlert(AlertEvent alertEvent) {
        String sensorId = alertEvent.getTriggeringReading() != null
                ? alertEvent.getTriggeringReading().getSensorId() : "unknown";
        MDC.put("correlationId", alertEvent.getCorrelationId());

        log.info("Received alert for notification: alertId={}, sensorId={}, severity={}",
                alertEvent.getAlertId(), sensorId, alertEvent.getSeverity());

        try {
            notificationService.processAlert(alertEvent);
            alertsHandled.increment();
            log.debug("Alert notification processed: {}", alertEvent.getAlertId());
        } catch (Exception e) {
            log.error("Error processing alert notification: alertId={}, error={}",
                    alertEvent.getAlertId(), e.getMessage(), e);
            throw e;  // Re-throw to trigger retry
        } finally {
            MDC.remove("correlationId");
        }
    }
}
