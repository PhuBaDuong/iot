package com.smarthome.notification.controller;

import com.smarthome.notification.entity.NotificationPreference;
import com.smarthome.notification.entity.NotificationRecord;
import com.smarthome.notification.repository.NotificationPreferenceRepository;
import com.smarthome.notification.repository.NotificationRecordRepository;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRecordRepository recordRepository;
    private final NotificationPreferenceRepository preferenceRepository;

    public NotificationController(NotificationRecordRepository recordRepository,
                                   NotificationPreferenceRepository preferenceRepository) {
        this.recordRepository = recordRepository;
        this.preferenceRepository = preferenceRepository;
    }

    // --- Notification History ---

    @GetMapping("/history")
    public List<NotificationRecord> getHistory(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "50") int limit) {
        Instant fromTs = from != null ? from : Instant.EPOCH;
        Instant toTs = to != null ? to : Instant.now();
        return recordRepository.findBySentAtBetweenOrderBySentAtDesc(fromTs, toTs, PageRequest.of(0, Math.min(limit, 1000)));
    }

    @GetMapping("/history/sensor/{sensorId}")
    public List<NotificationRecord> getBySensor(@PathVariable String sensorId,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return recordRepository.findBySensorIdOrderBySentAtDesc(sensorId, PageRequest.of(0, Math.min(limit, 1000)));
    }

    @GetMapping("/history/alert/{alertId}")
    public List<NotificationRecord> getByAlert(@PathVariable String alertId) {
        return recordRepository.findByAlertId(alertId);
    }

    // --- Preferences ---

    @GetMapping("/preferences")
    public List<NotificationPreference> getAllPreferences() {
        return preferenceRepository.findAll();
    }

    @GetMapping("/preferences/{userId}")
    public ResponseEntity<NotificationPreference> getPreference(@PathVariable String userId) {
        return preferenceRepository.findByUserId(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/preferences")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationPreference createPreference(@Valid @RequestBody NotificationPreference preference) {
        return preferenceRepository.save(preference);
    }

    @PutMapping("/preferences/{userId}")
    public ResponseEntity<NotificationPreference> updatePreference(
            @PathVariable String userId,
            @Valid @RequestBody NotificationPreference updated) {
        return preferenceRepository.findByUserId(userId)
                .map(existing -> {
                    existing.setEmailEnabled(updated.isEmailEnabled());
                    existing.setEmailAddress(updated.getEmailAddress());
                    existing.setSmsEnabled(updated.isSmsEnabled());
                    existing.setPhoneNumber(updated.getPhoneNumber());
                    existing.setWebhookEnabled(updated.isWebhookEnabled());
                    existing.setWebhookUrl(updated.getWebhookUrl());
                    existing.setMinSeverity(updated.getMinSeverity());
                    existing.setQuietHoursEnabled(updated.isQuietHoursEnabled());
                    existing.setQuietHoursStart(updated.getQuietHoursStart());
                    existing.setQuietHoursEnd(updated.getQuietHoursEnd());
                    return ResponseEntity.ok(preferenceRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/preferences/{userId}")
    public ResponseEntity<Void> deletePreference(@PathVariable String userId) {
        return preferenceRepository.findByUserId(userId)
                .map(pref -> {
                    preferenceRepository.delete(pref);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
