# IoT Smart Home Monitor — Architecture Guideline

## Overview

This project is a **microservices-based IoT monitoring system** built with Java 25 and Spring Boot 4.0. It simulates smart home sensors, ingests telemetry through a validation gateway, performs real-time analytics, persists historical data, and dispatches alert notifications — all secured by an OAuth 2.1 identity provider.

## High-Level Architecture

```
                         ┌──────────────────────┐
                         │   IAM Service (9000)  │
                         │  OAuth 2.1 / OIDC     │
                         │  Issues RS256 JWTs    │
                         └──────────┬───────────┘
                                    │ JWKS (public keys)
                    ┌───────────────┼───────────────────────┐
                    │               │                       │
                    ▼               ▼                       ▼
 ┌──────────────────────────────────────────────────────────────────┐
 │                     All Resource Servers                         │
 │  Validate JWT signature via /oauth2/jwks, extract roles claim   │
 └──────────────────────────────────────────────────────────────────┘

 ┌────────────────┐        RabbitMQ (AMQP/TLS)        ┌────────────────┐
 │ Sensor         │  sensor.{type}.{location}          │ Gateway        │
 │ Simulator ─────┼──────► sensor.exchange ──────────► │ Service        │
 │ (8081)         │                                    │ (8082)         │
 │                │  device.heartbeat                   │                │
 │                ├──────► sensor.exchange ──────┐      │ Validates,     │
 └────────────────┘                             │      │ dedupes,       │
                                                │      │ rate-limits,   │
                            ┌───────────────────┘      │ anomaly detect │
                            ▼                          └───┬────────┬───┘
                   ┌────────────────┐                      │        │
                   │ Device Registry│  data.processed      │        │ alert.anomaly
                   │ (8084)         │◄─────────────────────┘        │
                   │ Heartbeat +    │                               │
                   │ lifecycle mgmt │      ┌────────────────┐       │
                   └────────────────┘      │ Processing     │       │
                                           │ Service (8083) │◄──────┤
                                           │ Real-time      │       │
                                           │ analytics      │       │
                                           └────────────────┘       │
                                                                    │
                   ┌────────────────┐      ┌────────────────┐       │
                   │ History        │◄─────┤ data.processed │       │
                   │ Service (8085) │      │ (fan-out)      │       │
                   │ TimescaleDB    │      └────────────────┘       │
                   │ persistence    │                               │
                   └────────────────┘      ┌────────────────┐       │
                                           │ Notification   │◄──────┘
                                           │ Service (8086) │
                                           │ Alert dispatch │
                                           └────────────────┘
```

## Services

### 1. Sensor Simulator Service (Port 8081)

Generates synthetic sensor readings (temperature, humidity, light, pressure) every 2 seconds and publishes them to RabbitMQ. Also sends device heartbeats every 15 seconds and auto-registers with the Device Registry on startup.

### 2. Gateway Service (Port 8082)

The central data pipeline. Consumes raw sensor readings and applies:
- **Validation** — checks data format correctness
- **Deduplication** — Redis-backed, 5-minute TTL window
- **Rate limiting** — 120 messages/minute per sensor via Redis
- **Anomaly detection** — configurable thresholds per sensor type
- **Device verification** — validates source via Device Registry (circuit breaker protected)

Publishes validated data downstream (`data.processed`) and anomalies to the alerts exchange.

### 3. Processing Service (Port 8083)

Consumes processed events and maintains **in-memory per-sensor statistics** (count, average, min, max) with a sliding window for trend analysis. Exposes analytics via REST API.

### 4. Device Registry Service (Port 8084)

Authoritative store for device metadata and lifecycle. Consumes heartbeats to track device liveness, runs scheduled inactivity sweeps (marks devices INACTIVE after 5 minutes of silence), and publishes status changes via Redis pub/sub for gateway cache eviction.

**Database:** TimescaleDB `devices`

### 5. History Service (Port 8085)

Durably persists every processed reading to a TimescaleDB hypertable (`sensor_readings`). Provides historical query APIs and serves pre-computed 1-minute aggregates. Automatic compression kicks in for data older than 7 days.

**Database:** TimescaleDB `telemetry`

### 6. Notification Service (Port 8086)

Consumes anomaly alerts, deduplicates them, filters by user preferences and severity, and dispatches notifications. Maintains a notification history and audit trail.

**Database:** TimescaleDB `notifications`

### 7. IAM Service (Port 9000)

OAuth 2.1 / OpenID Connect Authorization Server (Spring Authorization Server). Authenticates users against PostgreSQL (BCrypt), issues RS256-signed JWTs with custom `roles`, `username`, and `email` claims. Bootstraps an admin user and three roles on first start.

**Database:** PostgreSQL `smarthome_iam`

### 8. Shared Library (iot-common)

Not a running service. Contains shared DTOs (`SensorReading`, `AlertEvent`, `HeartbeatEvent`), RabbitMQ constants, enums, and the `JwtAuthConverterFactory` used by all resource servers.

## Message Flow (RabbitMQ)

All messaging topology is pre-declared in `rabbitmq/definitions.json` and loaded on broker startup.

### Exchanges

| Exchange | Type | Purpose |
|----------|------|---------|
| `sensor.exchange` | Topic | All sensor data + heartbeats + processed events |
| `alerts.exchange` | Topic | Anomaly alerts |
| `dlx.exchange` | Direct | Dead-letter routing for failed messages |

### Queues & Bindings

| Queue | Routing Key | Consumer |
|-------|-------------|----------|
| `sensor.readings.queue` | `sensor.#` | Gateway Service |
| `processed.data.queue` | `data.processed` | Processing Service |
| `telemetry.persistence.queue` | `data.processed` | History Service |
| `device.heartbeat.queue` | `device.heartbeat` | Device Registry |
| `alerts.queue` | `alert.anomaly` | Notification Service |

Each queue has a corresponding dead-letter queue (e.g., `sensor.readings.dlq`) routed through `dlx.exchange`.

### Message Flow Summary

```
Simulator  ──► sensor.exchange ──► sensor.readings.queue ──► Gateway
                                                                │
                                  ┌─────────────────────────────┤
                                  │                             │
                                  ▼                             ▼
                          data.processed                  alert.anomaly
                                  │                             │
                    ┌─────────────┼──────────────┐              │
                    ▼             ▼               ▼             ▼
               Processing   History         (fan-out)    Notification
               Service       Service                      Service
```

## Inter-Service Communication

| From | To | Protocol | Purpose |
|------|----|----------|---------|
| Simulator | Device Registry | HTTP REST | Auto-register device on startup |

## Data Storage

### TimescaleDB / PostgreSQL (Single Instance, Multiple Databases)

| Database | Used By | Tables | Purpose |
|----------|---------|--------|---------|
| `telemetry` | History Service | `sensor_readings` (hypertable), `sensor_readings_1min` (continuous aggregate) | Time-series telemetry with automatic partitioning, compression (7d), and 1-minute rollups |
| `devices` | Device Registry | Device entity (JPA auto-created) | Device metadata and lifecycle tracking |
| `smarthome_iam` | IAM Service | User, Role, UserRole | Identity, credentials, role assignments |
| `notifications` | Notification Service | NotificationPreference, NotificationRecord | Alert preferences and audit trail |

Database initialization scripts in `db/timescaledb/init/` run automatically on first container start.

### Redis

| Used By | Purpose |
|---------|---------|
| Gateway Service | Deduplication keys (`dedup::{sensorId}::{readingId}`, TTL 300s), rate-limit counters, device status cache |
| Device Registry | Publishes device status changes via pub/sub channel `device.status.changed` |

## Authentication & Authorization

### Flow

1. User authenticates with IAM Service (`/oauth2/token`)
2. IAM validates credentials (BCrypt) and issues an RS256-signed JWT containing `roles`, `username`, `email` claims
3. Client sends JWT as `Authorization: Bearer <token>` on subsequent requests
4. Each resource server fetches the IAM public key from `/oauth2/jwks` (lazy-loaded, cached)
5. Resource server validates signature, extracts roles, and enforces access control

### Roles

| Role | Access Level |
|------|--------------|
| `VIEWER` | Read-only: stats, analytics, history, notifications |
| `OPERATOR` | VIEWER + simulation control (start/stop/trigger), alert management |
| `ADMIN` | Full control: user management, threshold modification, device decommission |

## Observability

### Prometheus (Port 9090)

Scrapes `/actuator/prometheus` from all services every 15 seconds. Custom metrics include:
- `sensor.readings.published`, `gateway.anomalies.detected`, `processing.readings.processed`
- `registry.heartbeats.received`, `history.persistence.success`, `notification.alerts.received`

### Grafana (Port 3000)

Dashboards backed by Prometheus. Auto-provisioned data source. Default credentials: `admin / admin`.

### Zipkin (Port 9411)

Distributed tracing — all services send spans (100% sampling). Correlation IDs propagated through MDC for structured logging.

### Health Checks

Every service exposes `/actuator/health`. Docker Compose uses these for container health checks with start period, interval, and retry configuration.

## RabbitMQ Security

- **TLS** on port 5671 (certificates generated via `certs/generate-certs.sh`)
- **Per-service credentials** — each service has its own user with least-privilege permissions
- **Immutable topology** — no service can create or delete exchanges/queues; all pre-declared in `definitions.json`

## Resilience Patterns

| Pattern | Where | Detail |
|---------|-------|--------|
| Circuit Breaker | Gateway → Device Registry | Resilience4j; falls back to UNVERIFIED status |
| Circuit Breaker | Gateway → RabbitMQ publish | Resilience4j; prevents cascade on broker issues |
| Retry | Gateway message processing | Exponential backoff (3 attempts, 500ms initial, 2x multiplier) |
| Dead-Letter Queues | All queues | Failed messages routed to DLQs for inspection/replay |
| Deduplication | Gateway (Redis) | Prevents duplicate processing within 5-minute window |
| Rate Limiting | Gateway (Redis) | 120 messages/minute per sensor |
| Inactivity Sweep | Device Registry | Marks devices INACTIVE after 5 minutes of silence |

## Port Reference

| Service / Component | Port |
|---------------------|------|
| Sensor Simulator | 8081 |
| Gateway Service | 8082 |
| Processing Service | 8083 |
| Device Registry Service | 8084 |
| History Service | 8085 |
| Notification Service | 8086 |
| IAM Service | 9000 |
| RabbitMQ (AMQP) | 5672 |
| RabbitMQ (AMQPS/TLS) | 5671 |
| RabbitMQ Management UI | 15672 |
| Redis | 6379 |
| TimescaleDB (PostgreSQL) | 5432 |
| Zipkin | 9411 |
| Prometheus | 9090 |
| Grafana | 3000 |
