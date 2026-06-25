-- =============================================================================
-- 01 - Create the relational database for the Device Registry
-- =============================================================================
-- The container's POSTGRES_DB bootstraps the "telemetry" database (used by the
-- history-service hypertable). The Device Registry stores relational device
-- metadata in a separate "devices" database on the same TimescaleDB instance.
-- This init script runs once, on first container startup, against "telemetry".
-- =============================================================================
CREATE DATABASE devices;
