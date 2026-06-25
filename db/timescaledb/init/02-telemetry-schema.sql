-- =============================================================================
-- 02 - Telemetry schema: sensor_readings hypertable (history-service)
-- =============================================================================
-- Runs against the default "telemetry" database. Creates the TimescaleDB
-- extension and the time-partitioned hypertable that the history-service maps
-- to with JPA (ddl-auto: none). Compression is enabled with a 7-day policy.
-- =============================================================================
CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE IF NOT EXISTS sensor_readings (
    time                TIMESTAMPTZ      NOT NULL,
    reading_id          TEXT,
    sensor_id           TEXT             NOT NULL,
    sensor_type         TEXT             NOT NULL,
    value               DOUBLE PRECISION,
    unit                TEXT,
    location            TEXT,
    correlation_id      TEXT,
    anomaly             BOOLEAN          NOT NULL DEFAULT FALSE,
    anomaly_description TEXT
);

-- Convert to a hypertable partitioned on the time column.
SELECT create_hypertable('sensor_readings', 'time', if_not_exists => TRUE);

-- Hot query path: "latest readings for a given sensor".
CREATE INDEX IF NOT EXISTS idx_sensor_readings_sensor_time
    ON sensor_readings (sensor_id, time DESC);

-- Native columnar compression for IoT workloads (10-20x typical), applied to
-- chunks older than 7 days.
ALTER TABLE sensor_readings SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'sensor_id'
);
SELECT add_compression_policy('sensor_readings', INTERVAL '7 days', if_not_exists => TRUE);
