-- =============================================================================
-- 01 - Create the relational database for the Device Registry
-- =============================================================================
-- The container's POSTGRES_DB bootstraps the "telemetry" database (used by the
-- history-service hypertable). The Device Registry stores relational device
-- metadata in a separate "devices" database on the same TimescaleDB instance.
-- This init script runs once, on first container startup, against "telemetry".
--
-- Phase 3A adds a third database, "smarthome_iam", for the IAM service
-- (users, roles, user_roles).
-- =============================================================================
CREATE DATABASE devices;
CREATE DATABASE smarthome_iam;
