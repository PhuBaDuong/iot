# IoT Smart Home Monitor — Comprehensive Project Overview

---

## 1. Project Overview

The IoT Smart Home Monitor is an **event-driven microservices platform** that ingests synthetic sensor telemetry, validates and enriches it, runs real-time analytics and anomaly detection, persists it durably, manages device lifecycles, and delivers multi-channel alert notifications — all secured end-to-end.

**Core technology choices:**

| Concern | Choice |
|---|---|
| Language / Runtime | **Java 25** |
| Framework | **Spring Boot 4.0.1** |
| Security | **Spring Security 7.1** with Spring Authorization Server (OAuth 2.1 / OIDC) |
| Messaging | **RabbitMQ 3.13** — TLS-encrypted, topic exchanges, per-queue DLQs, per-service credentials |
| Resilience | **Spring Cloud 2025.1.2 (Oakwood)** — Resilience4j circuit breakers and retry |
| Time-series store | **TimescaleDB** (PostgreSQL 16 extension) — hypertable with compression and continuous aggregates |
| Cache | **Redis 7** — deduplication, rate limiting, threshold config, device-status cache, pub/sub invalidation |
| Observability | **Micrometer → Prometheus → Grafana** (metrics), **OpenTelemetry → Zipkin** (tracing), structured JSON logging (Logstash encoder) |
| API entry point | **Spring Cloud Gateway** (reactive/Netty) — JWT validation, Redis-backed rate limiting |
| Transport security | **mTLS** on all internal service-to-service REST, TLS on AMQP |

The **architectural pattern** is asynchronous, event-driven microservices communicating via RabbitMQ topic exchanges. There are no synchronous inter-service calls on the hot telemetry path — the gateway publishes enriched events and alert events to separate routing keys, and downstream services consume independently via fan-out bindings. REST is used only for query APIs, device registration, and admin operations, all routed through the Spring Cloud API Gateway.

---

## 2. Module Breakdown

The Maven reactor (`com.smarthome:iot-parent:1.0.0-SNAPSHOT`) contains **nine modules**:

### `iot-common` (shared library, no port)

The shared foundation. Contains DTOs (`SensorReading`, `SensorDataEvent`, `AlertEvent`, `HeartbeatEvent`), enums (`SensorType`), centralized RabbitMQ constant definitions (`RabbitMQConstants` — all exchange, queue, and routing key names in one place), and the `JwtAuthConverterFactory` that maps JWT `roles` claims to Spring Security `ROLE_*` authorities. By centralizing these, it prevents string mismatches across services and provides a uniform security integration point.

### `sensor-simulator-service` (port 8081)

The **data producer** — the entry point of the entire pipeline. Generates synthetic readings from seven configured sensors (temperature, humidity, motion, light, pressure) every ~2 seconds and publishes them to `sensor.exchange` with dynamic routing keys (`sensor.{type}.{location}`). Also sends `HeartbeatEvent` messages every ~15 seconds (routing key `device.heartbeat`). On startup, auto-registers its simulated sensors with the device registry via REST.

### `gateway-service` (port 8082)

The **central ingestion and enrichment hub** — the highest-throughput chokepoint. Every raw reading passes through a multi-step pipeline:

1. **Deduplication** — Redis `SET NX` with 5-minute TTL (first check, short-circuits duplicates)
2. **Rate limiting** — Redis `INCR`/`EXPIRE` fixed-window counters per sensor (120/min default)
3. **Validation** — required fields, physical value ranges, timestamp sanity
4. **Device verification** — cached REST call to device registry (Redis 60s TTL, pub/sub invalidation, circuit breaker fallback)
5. **Anomaly detection** — compares values against Redis-cached thresholds (fallback to YAML defaults)

Emits `SensorDataEvent` (routing key `data.processed`) for all readings and `AlertEvent` (routing key `alert.anomaly`) when anomalies are detected. Exposes a DLQ depth monitor as a Prometheus gauge and an admin API for runtime threshold updates.

### `processing-service` (port 8083)

The **real-time analytics engine**. Consumes from `processed.data.queue` and maintains in-memory sliding-window statistics (per-sensor averages, counts, min/max). Serves these via `/api/analytics/**`. This is a hot-path, low-latency service — it does not persist data (that's the history service's job). Alert handling was migrated out to the notification service.

### `device-registry-service` (port 8084)

The **device lifecycle manager** and single source of truth for sensor metadata. Maintains a `devices` table in PostgreSQL with a state machine: `PROVISIONED → ACTIVE → INACTIVE ↔ ACTIVE → DECOMMISSIONED`. Consumes `device.heartbeat.queue` to update `lastSeenAt` and promote devices to `ACTIVE`. A scheduled sweep flips silent devices to `INACTIVE` and publishes `device.status.changed` to Redis pub/sub so the gateway evicts stale cache entries. Exposes CRUD + lifecycle REST endpoints.

### `history-service` (port 8085)

The **durable telemetry persistence layer**. Binds its own queue (`telemetry.persistence.queue`) to `sensor.exchange` on `data.processed` — this is **fan-out**, not competing consumers with processing-service. Every processed event is persisted into a TimescaleDB hypertable (`sensor_readings`) partitioned by time, with compression for chunks older than 7 days and a 1-minute continuous aggregate (`sensor_readings_1min`). Duplicate `reading_id` inserts are handled idempotently. Exposes `/api/history/**` query endpoints.

### `iam-service` (port 9000)

The **OAuth 2.1 / OIDC Authorization Server**. Built on Spring Authorization Server. Issues RS256-signed JWTs with custom `roles`, `username`, and `email` claims. Hosts JWKS (`/oauth2/jwks`) for all resource servers to validate tokens. Manages users/roles in a PostgreSQL `smarthome_iam` database (seeded with ADMIN, OPERATOR, VIEWER roles and a bootstrap admin). Registers both public clients (SPA with PKCE) and confidential service clients (`client_credentials` grant for service-to-service auth).

### `notification-service` (port 8086)

The **alert dispatch and audit system**. Consumes `alerts.queue`, deduplicates alerts by `alertId`, checks user-configured severity thresholds and quiet hours from `notification_preferences`, and dispatches through channel abstractions (email/SMS/webhook — currently stub implementations). Every dispatch attempt is recorded in `notification_records` for audit. Exposes REST APIs for notification history and preference management.

### `api-gateway` (port 8080)

The **single entry point** for all external REST traffic. Built on Spring Cloud Gateway (reactive/Netty). Routes to all 7 backend services. Validates IAM-issued RS256 JWTs via JWKS. Applies Redis-backed rate limiting (20 req/s per principal, burst 40). Logs all requests via a global `RequestLoggingFilter`. In Docker, routes to HTTPS backends (mTLS).

---

## 3. Real-World Problem Solving

### Secure Device Authentication (OAuth 2.1 / mTLS)

The system addresses the IoT "who can talk to what" problem at multiple layers:

- **Human users** authenticate via the IAM service (OAuth 2.1 Authorization Code + PKCE for SPAs, username/password + form login). JWTs carry role claims (ADMIN/OPERATOR/VIEWER), and every REST endpoint enforces RBAC via `@PreAuthorize`. All six app services validate tokens by fetching the IAM JWKS endpoint lazily — no hard startup dependency.
- **Service-to-service REST** is secured with **mTLS**: every service has a PKCS12 keystore signed by a project CA, with `client-auth: need`. This ensures only authenticated services can communicate internally.
- **Broker-level security**: RabbitMQ runs TLS on port 5671, and each service connects with its own least-privilege credential (e.g., `simulator-svc` can only write to `sensor.exchange`; `processing-svc` can only read from `processed.data.queue`). This prevents compromised services from accessing queues they shouldn't touch.

### Scalable Telemetry Ingestion

The architecture handles the fundamental IoT challenge of high-volume, bursty data at the gateway:

- **Redis deduplication** (`SET NX`, 5-min TTL) is the *first check*, short-circuiting duplicates before any processing — critical when publisher confirms and retries can cause duplicate delivery.
- **Per-sensor rate limiting** (Redis `INCR`/`EXPIRE` fixed-window) protects downstream services from flooding by misbehaving sensors.
- **Fan-out via topic exchange bindings** lets the processing service and history service receive the same events independently without competing — analytics stays real-time while persistence stays durable.
- **Dead-letter queues** with Resilience4j retry (3 attempts, exponential backoff 1–10s) ensure no data is silently lost; the `DlqMonitorService` exposes DLQ depth as a Prometheus gauge for alerting.
- **Circuit breakers** around the device registry REST call (with fallback to accepting readings as `unverified`) prevent cascading failures when a downstream service is degraded.
- **Horizontal scaling** is supported natively — competing consumers on any queue, and HPA is configured for gateway, processing, and api-gateway in the Helm charts.

### Real-Time Anomaly Detection

The gateway's `AnomalyDetectionService` compares each reading against per-sensor-type thresholds (e.g., temperature 15–30°C). These thresholds are:

- Stored in Redis hashes (`config:thresholds:{sensorType}`) with YAML fallback defaults.
- Dynamically updatable at runtime via `PUT /api/gateway/thresholds/{sensorType}` — no redeployment required.
- When breached, an `AlertEvent` is published to `alerts.exchange` with routing key `alert.anomaly`, consumed by the notification service which deduplicates, checks user severity preferences and quiet hours, and dispatches through configured channels.

### Durable Time-Series Data Persistence

The history service solves the IoT data retention problem using **TimescaleDB**:

- A **hypertable** (`sensor_readings`) partitioned by time for efficient time-range queries.
- **Automatic compression** for chunks older than 7 days (10–20× compression for IoT workloads).
- A **continuous aggregate** (`sensor_readings_1min`) pre-computes avg/min/max/count at 1-minute granularity, enabling dashboard queries without scanning raw data.
- **Idempotent inserts** by `reading_id` ensure exactly-once semantics even with at-least-once delivery from RabbitMQ.
- The system stays entirely within the **PostgreSQL ecosystem** (TimescaleDB is a Postgres extension), meaning a single operational skill set covers telemetry, device registry, IAM, and notification databases — all hosted on the same Postgres 16 instance with separate databases.
