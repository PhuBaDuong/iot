-- =============================================================================
-- 03 - Continuous aggregate: 1-minute rollups (dashboards / history API)
-- =============================================================================
-- A materialized, incrementally-refreshed view that pre-computes avg/min/max
-- and sample counts per sensor per minute. The history-service serves
-- coarse-granularity queries from here instead of scanning raw rows.
-- =============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS sensor_readings_1min
    WITH (timescaledb.continuous) AS
SELECT sensor_id,
       sensor_type,
       time_bucket(INTERVAL '1 minute', time) AS bucket,
       avg(value)  AS avg_value,
       min(value)  AS min_value,
       max(value)  AS max_value,
       count(*)    AS sample_count
FROM sensor_readings
GROUP BY sensor_id, sensor_type, bucket
WITH NO DATA;

-- Keep the aggregate fresh: refresh data between 1 hour ago and 1 minute ago,
-- every minute.
SELECT add_continuous_aggregate_policy('sensor_readings_1min',
    start_offset      => INTERVAL '1 hour',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute',
    if_not_exists     => TRUE);
