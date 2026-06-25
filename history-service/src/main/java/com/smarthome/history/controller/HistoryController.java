package com.smarthome.history.controller;

import com.smarthome.history.dto.ReadingPointDto;
import com.smarthome.history.entity.SensorReadingEntity;
import com.smarthome.history.repository.SensorReadingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * =============================================================================
 * HistoryController - read API over persisted telemetry
 * =============================================================================
 *   GET /api/history/{sensorId}          ?from=&to=&limit=  time-window query
 *   GET /api/history/{sensorId}/latest   ?limit=            most-recent readings
 * Timestamps are ISO-8601 instants (e.g. 2026-06-25T10:15:30Z).
 * =============================================================================
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

    private static final int MAX_LIMIT = 1000;

    private final SensorReadingRepository repository;

    public HistoryController(SensorReadingRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/{sensorId}")
    public List<ReadingPointDto> query(
            @PathVariable String sensorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "100") int limit) {

        Pageable page = PageRequest.of(0, clamp(limit));
        Instant fromTs = from != null ? from : Instant.EPOCH;
        Instant toTs = to != null ? to : Instant.now();
        List<SensorReadingEntity> rows =
                repository.findBySensorIdAndTimeBetweenOrderByTimeDesc(sensorId, fromTs, toTs, page);
        return rows.stream().map(ReadingPointDto::from).toList();
    }

    @GetMapping("/{sensorId}/latest")
    public List<ReadingPointDto> latest(@PathVariable String sensorId,
                                        @RequestParam(defaultValue = "10") int limit) {
        Pageable page = PageRequest.of(0, clamp(limit));
        return repository.findBySensorIdOrderByTimeDesc(sensorId, page).stream()
                .map(ReadingPointDto::from).toList();
    }

    private static int clamp(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
