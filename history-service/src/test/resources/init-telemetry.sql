-- =============================================================================
-- Test schema for the history-service integration test
-- =============================================================================
-- Minimal subset of db/timescaledb/init/02-telemetry-schema.sql: the extension,
-- the sensor_readings table, and its hypertable. Compression/aggregate policies
-- are omitted to keep the container start fast.
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

SELECT create_hypertable('sensor_readings', 'time', if_not_exists => TRUE);

CREATE INDEX IF NOT EXISTS idx_sensor_readings_sensor_time
    ON sensor_readings (sensor_id, time DESC);
