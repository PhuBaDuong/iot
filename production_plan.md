# IoT Smart Home Monitor — Production-Readiness Architectural Roadmap

## Current State Assessment

The codebase is a well-structured **educational prototype** with three services and a shared library:

| Service | Port | Role |
|---|---|---|
| `sensor-simulator-service` | 8081 | Generates synthetic sensor data on a schedule, publishes to RabbitMQ |
| `gateway-service` | 8082 | Validates readings, detects anomalies, enriches with metadata, routes downstream |
| `processing-service` | 8083 | Consumes processed events, maintains in-memory analytics, handles alerts (log-only) |
| `iot-common` | — | Shared DTOs (`SensorReading`, `SensorDataEvent`, `AlertEvent`), constants, enums |

**What works well:** Topic exchange routing, correlation IDs, configurable thresholds, Jackson serialization, builder-pattern DTOs, Spring configuration properties, durable queues.

**Critical gaps for production:** No persistence, no security, no DLQs, no circuit breakers, no real alerting, no device management, no observability stack, no container orchestration.

---

## 1. Missing Microservices

### 1.1 Identity & Access Management (IAM) Service

**Responsibility:** Authenticate users, authorize devices, issue and validate JWT tokens, manage API keys for physical sensors.

| Aspect | Detail |
|---|---|
| **Auth model** | OAuth 2.1 / OpenID Connect via Spring Authorization Server |
| **Device auth** | X.509 client certificates or pre-provisioned API keys stored in a `device_credentials` table |
| **User auth** | Username/password + optional TOTP MFA; JWT access tokens (short-lived, 15 min) + opaque refresh tokens (long-lived, 7 days) |
| **Token propagation** | Every RabbitMQ message header carries a `jwt` claim; gateway validates signature before processing. REST endpoints protected by Spring Security resource server filters |
| **Storage** | PostgreSQL — `users`, `roles`, `permissions`, `device_credentials`, `refresh_tokens` tables |
| **Key endpoints** | `POST /auth/token`, `POST /auth/refresh`, `POST /auth/revoke`, `GET /auth/.well-known/jwks.json`, `POST /devices/{id}/credentials` |

**Integration with existing services:**
- Gateway adds a `JwtValidationFilter` that rejects unsigned or expired messages before they enter the validation pipeline.
- REST controllers across all services add `@PreAuthorize` annotations; the `SecurityFilterChain` bean validates JWTs against the IAM service's JWKS endpoint.

### 1.2 Device Registry Service

**Responsibility:** Single source of truth for every physical sensor's metadata, lifecycle state, firmware version, and health.

| Aspect | Detail |
|---|---|
| **Data model** | `Device { id, name, sensorType, location, firmwareVersion, provisionedAt, lastSeenAt, status [PROVISIONED, ACTIVE, INACTIVE, DECOMMISSIONED], owner, tags }` |
| **Lifecycle** | State machine: `PROVISIONED → ACTIVE → INACTIVE ↔ ACTIVE → DECOMMISSIONED` |
| **Heartbeat** | Consumes a `device.heartbeat` routing key on `sensor.exchange`; updates `lastSeenAt`. A scheduled job marks devices `INACTIVE` after configurable silence window (default 5 min) |
| **Storage** | PostgreSQL with a `devices` table; Redis cache for hot lookups by `sensorId` |
| **Key endpoints** | CRUD on `/api/devices`, `GET /api/devices/{id}/history`, `POST /api/devices/{id}/decommission` |

**Integration with existing services:**
- The `gateway-service` calls the registry (via REST or cache) on every incoming reading to verify the sensor is `ACTIVE`. Readings from unknown or decommissioned devices are rejected and routed to a DLQ.
- The `sensor-simulator-service` auto-registers its simulated sensors on startup via `POST /api/devices`.

### 1.3 Notification Service

**Responsibility:** Fan-out alerts to human-facing channels — email, SMS, push notifications, and webhook integrations.

| Aspect | Detail |
|---|---|
| **Input** | Listens on `alerts.queue` (same as today's `AlertListener`), replacing the log-only handler |
| **Channels** | Email (SendGrid/SES), SMS (Twilio), Push (Firebase Cloud Messaging), Webhook (configurable HTTP POST) |
| **Routing rules** | Per-user notification preferences stored in PostgreSQL: `notification_preferences { userId, channel, severityFilter, sensorFilter, quietHours }` |
| **Deduplication** | Sliding-window dedup by `(sensorId, sensorType, severity)` — suppress repeated alerts within configurable cooldown (default 5 min) |
| **Template engine** | Thymeleaf or FreeMarker templates for email bodies; parameterized SMS templates |
| **Key endpoints** | `GET/PUT /api/notifications/preferences`, `GET /api/notifications/history` |

**Integration with existing services:**
- `processing-service` relinquishes alert handling to this new service. The `AlertListener` and `AlertHandlerService` in `processing-service` are removed; those queues become owned by the Notification Service.

### 1.4 Persistence / History Service

**Responsibility:** Durable long-term storage of all sensor telemetry and processed events, replacing in-memory `ConcurrentHashMap` analytics.

| Aspect | Detail |
|---|---|
| **Telemetry store** | **TimescaleDB** (PostgreSQL extension) — hypertable `sensor_readings` partitioned by time with automatic compression after 7 days and retention policies (e.g., raw data 90 days, 1-min aggregates 1 year, 1-hour aggregates indefinitely) |
| **Why TimescaleDB** | Stays within the PostgreSQL ecosystem (single operational skill set), supports standard SQL, continuous aggregates for dashboards, native compression (10–20× for IoT workloads), and integrates with existing Spring Data JPA |
| **Input** | *(As built)* Binds its **own** queue (`telemetry.persistence.queue`) to `sensor.exchange` on the `data.processed` routing key — fan-out delivery, so it persists every processed event independently of the processing-service. It is **not** a competing consumer on `processed.data.queue` |
| **Schema** | *(As built)* `sensor_readings (time TIMESTAMPTZ, reading_id TEXT, sensor_id TEXT, sensor_type TEXT, value DOUBLE PRECISION, unit TEXT, location TEXT, correlation_id TEXT, anomaly BOOLEAN, anomaly_description TEXT)` — hypertable on `time` (no `metadata JSONB`; `correlation_id` stored as TEXT) |
| **Continuous aggregates** | *(As built)* One materialized view `sensor_readings_1min` (`avg`/`min`/`max`/`count` at 1-min granularity, refreshed every minute) + a 7-day compression policy. Coarser 15-min/1-hour/1-day rollups and time-based retention policies remain TODO |
| **Key endpoints** | `GET /api/history/{sensorId}?from=&to=&granularity=`, `GET /api/history/export?format=csv` |

**Integration with existing services:**
- `processing-service` retains its real-time in-memory sliding window for hot-path queries (last 100 readings) but delegates historical queries to the History Service.
- Dashboards and reporting tools query TimescaleDB directly or through Grafana.

---

## 2. Design & Infrastructure Improvements

### 2.1 Data Persistence Strategy

| Data Category | Technology | Rationale |
|---|---|---|
| Sensor telemetry (time-series) | **TimescaleDB** | SQL-compatible, compression, continuous aggregates, single Postgres skill set |
| Relational data (users, devices, preferences) | **PostgreSQL 16** | Mature, transactional, Spring Data JPA support |
| Hot cache (device registry lookups, session tokens) | **Redis 7** | Sub-ms reads, TTL-based eviction, pub/sub for invalidation |
| Message broker | **RabbitMQ 3.13** (upgrade) | Quorum queues for HA, streams for replay |

### 2.2 Resilience & Reliability

#### A. RabbitMQ Topology Hardening

The current topology has no dead-letter handling. Target topology:

```
sensor.exchange (topic, durable)
  ├─ sensor.# ──────────────► sensor.readings.queue
  │                              x-dead-letter-exchange: dlx.exchange
  │                              x-dead-letter-routing-key: dlx.sensor.readings
  │                              x-message-ttl: 300000 (5 min)
  │
  └─ data.processed ────────► processed.data.queue
                                 x-dead-letter-exchange: dlx.exchange
                                 x-dead-letter-routing-key: dlx.processed.data

alerts.exchange (topic, durable)
  └─ alert.anomaly ──────────► alerts.queue
                                 x-dead-letter-exchange: dlx.exchange
                                 x-dead-letter-routing-key: dlx.alerts

dlx.exchange (direct, durable)        ← NEW
  ├─ dlx.sensor.readings ───► sensor.readings.dlq     ← NEW
  ├─ dlx.processed.data ────► processed.data.dlq      ← NEW
  └─ dlx.alerts ─────────────► alerts.dlq              ← NEW
```

Changes to implement in each service's `RabbitMQConfig`:
- Add `x-dead-letter-exchange` and `x-dead-letter-routing-key` arguments to every queue declaration.
- Declare a `dlx.exchange` (direct) and three `.dlq` queues.
- Add a `DlqMonitorService` that periodically logs DLQ depth and exposes it via `/actuator/metrics`.

#### B. Retry Policies

Current config uses Spring Retry on the listener container. Enhance with:
- **Listener retry:** Keep `max-attempts: 3` with exponential backoff (`initial: 1s, multiplier: 2.0, max: 10s`).
- **After exhaustion:** `RejectAndDontRequeueRecoverer` to route to DLQ (not infinite requeue).
- **Publisher confirms:** Enable `spring.rabbitmq.publisher-confirm-type: correlated` and `publisher-returns: true` on gateway and simulator to detect unroutable messages.

#### C. Circuit Breakers (Resilience4j)

Add `spring-cloud-starter-circuitbreaker-resilience4j` to gateway and processing services:

| Circuit Breaker | Wraps | Config |
|---|---|---|
| `deviceRegistryBreaker` | REST call to Device Registry | `failureRateThreshold: 50`, `waitDurationInOpenState: 30s`, `slidingWindowSize: 10` — fallback: accept reading but flag as `unverified` |
| `notificationBreaker` | REST/AMQP call to Notification Service | `failureRateThreshold: 50`, `waitDurationInOpenState: 60s` — fallback: write alert to local DLQ file |
| `persistenceBreaker` | Write to TimescaleDB | `failureRateThreshold: 30`, `waitDurationInOpenState: 15s` — fallback: buffer in Redis, flush on close |

Add `@Retry` and `@CircuitBreaker` annotations to service methods; expose breaker state via `/actuator/circuitbreakers`.

### 2.3 Security

#### A. Broker Security

| Layer | Mechanism |
|---|---|
| Transport | TLS 1.3 for AMQP connections (`spring.rabbitmq.ssl.enabled: true`); terminate with broker-side certificates |
| Authentication | Replace plain `smarthome/smarthome123` with per-service credentials managed via Vault or K8s Secrets |
| Authorization | RabbitMQ topic-based permissions: simulator can only `write` to `sensor.exchange`; gateway can `read` from `sensor.readings.queue` and `write` to both exchanges; processing can only `read` |
| Virtual Hosts | Separate vhost per environment (`/production`, `/staging`) |

#### B. Inter-Service Communication

| Layer | Mechanism |
|---|---|
| Service-to-service REST | mTLS via Spring Boot's `server.ssl` + service mesh (Istio) in K8s |
| API Gateway | Spring Cloud Gateway in front of all REST endpoints; validates JWT, rate-limits, routes |
| Message-level | Sign message payloads with HMAC-SHA256; include `signature` header; verify on consume |
| Secrets management | HashiCorp Vault with Spring Cloud Vault; rotate credentials automatically |

#### C. JWT Validation Flow

```
Sensor/Client → [API Gateway] → JWT signature check (JWKS from IAM)
                                   ↓ valid
                              Route to target service
                                   ↓ invalid
                              401 Unauthorized
```

For AMQP messages: gateway embeds a signed claim in message headers; downstream consumers verify the claim with IAM's public key (cached locally, refreshed every 5 min).

### 2.4 Observability

#### A. Distributed Tracing

- Add `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-zipkin` to all services.
- The existing `correlationId` in `SensorDataEvent` and `AlertEvent` maps naturally to a trace/span ID. Instrument `RabbitTemplate` and `@RabbitListener` with `ObservationRegistry` (Spring Boot 4.x auto-configures this).
- Deploy **Zipkin** or **Jaeger** as a trace collector.
- Trace propagation: W3C `traceparent` header injected into AMQP message properties automatically by Micrometer.

#### B. Centralized Logging

| Component | Role |
|---|---|
| **Logback** (already present) | Structured JSON log output (`logstash-logback-encoder`) |
| **Promtail** / **Fluent Bit** | Sidecar log shippers in K8s |
| **Loki** (recommended) or **Elasticsearch** | Log aggregation and indexing |
| **Grafana** | Unified dashboards — logs + metrics + traces in one pane |

Log format: every log line includes `correlationId`, `service`, `sensorId`, `traceId` as structured fields for cross-service correlation.

#### C. Metrics & Health Monitoring

- Add `spring-boot-starter-actuator` + `micrometer-registry-prometheus` to all services.
- Key custom metrics to expose:

| Metric | Type | Service |
|---|---|---|
| `sensor.readings.published` | Counter | simulator |
| `gateway.messages.received` | Counter | gateway |
| `gateway.validation.failures` | Counter | gateway |
| `gateway.anomalies.detected` | Counter | gateway |
| `processing.events.processed` | Counter | processing |
| `processing.alerts.handled` | Counter | processing |
| `rabbitmq.queue.depth` | Gauge | all (via management plugin) |
| `device.registry.cache.hit.ratio` | Gauge | gateway |

- Deploy **Prometheus** to scrape `/actuator/prometheus` endpoints.
- Deploy **Grafana** dashboards: system overview, per-sensor drill-down, alert rates, queue depths, circuit breaker states.
- **Alerting rules** in Prometheus/Alertmanager: DLQ depth > 0, circuit breaker open, service down, anomaly rate spike.

### 2.5 Gateway Redis Cache Layer

The gateway is the highest-throughput chokepoint — every sensor reading passes through it. Adding a Redis cache directly at this layer eliminates expensive repeated work on the hot path.

#### A. Device Status Cache

Once the Device Registry exists (Phase 2), the gateway must verify every incoming `sensorId` is `ACTIVE`. Without caching, this is a synchronous REST call per reading. Redis eliminates the majority of those round-trips:

```
SensorReading arrives
  → Redis GET device:{sensorId}:status
    → HIT  → use cached status (sub-ms)
    → MISS → REST call to Device Registry → cache result with TTL 60s
```

- **Key pattern:** `device:{sensorId}:status`
- **Value:** JSON `{ "status": "ACTIVE", "location": "living-room", "sensorType": "TEMPERATURE" }`
- **TTL:** 60 seconds (balances freshness vs. load; tunable per environment)
- **Invalidation:** Device Registry publishes a `device.status.changed` event to a Redis pub/sub channel; gateway subscribes and evicts the key immediately on state change.

Spring integration: `spring-boot-starter-data-redis` + `@Cacheable("deviceStatus")` on the registry client method, with `RedisCacheManager` configured for TTL.

#### B. Message Deduplication

The codebase currently has no dedup protection. With publisher confirms and retries enabled (Phase 1), the same reading can be delivered more than once. Redis provides lightweight exactly-once semantics:

```
SensorReading arrives with readingId = "abc-123"
  → Redis SET reading:abc-123 EX 300 NX
    → OK (key created)    → process normally
    → nil (key existed)   → discard as duplicate, log and increment counter
```

- **Key pattern:** `reading:{readingId}`
- **Value:** `1` (presence-only)
- **TTL:** 300 seconds (5 minutes covers any realistic retry window)
- **Metric:** `gateway.readings.deduplicated` counter exposed via Micrometer

This should be the **first check** in `SensorDataListener.handleSensorReading()`, before validation or enrichment, to short-circuit duplicates as early as possible.

#### C. Per-Device Rate Limiting

Detect flooding or misbehaving sensors before they consume downstream resources:

```
SensorReading arrives from sensorId = "temp-sensor-001"
  → Redis INCR ratelimit:{sensorId}:{currentMinute}
  → Redis EXPIRE ratelimit:{sensorId}:{currentMinute} 120
    → count > threshold (e.g., 60/min) → reject, route to DLQ, log warning
    → count ≤ threshold               → proceed normally
```

- **Key pattern:** `ratelimit:{sensorId}:{minuteTimestamp}`
- **Threshold:** Configurable per sensor type (e.g., temperature: 60/min, motion: 120/min)
- **Metric:** `gateway.readings.rate_limited` counter

#### D. Threshold Configuration Cache

The current `ThresholdConfig` is loaded from `application.yml` at startup and requires a restart to change. With Redis:

- Store thresholds in Redis hash `config:thresholds:{sensorType}` with fields `min`, `max`.
- `AnomalyDetectionService` reads from Redis (falling back to YAML defaults on cache miss).
- An admin API (`PUT /api/gateway/thresholds/{sensorType}`) updates Redis — changes take effect on the next reading without redeployment.
- **TTL:** None (persistent); invalidation via explicit write.

#### E. Infrastructure

Add Redis to Docker Compose alongside RabbitMQ:

```yaml
redis:
  image: redis:7-alpine
  container_name: smarthome-redis
  ports:
    - "6379:6379"
  command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
  volumes:
    - redis_data:/data
  healthcheck:
    test: ["CMD", "redis-cli", "ping"]
    interval: 10s
    timeout: 5s
    retries: 5
```

Gateway dependency: `spring-boot-starter-data-redis` + `spring-boot-starter-cache` in `gateway-service/pom.xml`.

---

## 3. Implementation Roadmap

Prioritized into four phases, each building on the previous. Estimated timelines assume a small team (2–3 engineers).

---

### Phase 1 — Foundation & Resilience (Weeks 1–3)

*Goal: Make the existing three services production-hardened without adding new services.*

| Step | Action | Services Affected |
|---|---|---|
| 1.1 | **Add Spring Boot Actuator + Prometheus metrics** to all services. Expose `/actuator/health`, `/actuator/prometheus`. Replace `AtomicLong` counters with Micrometer `Counter`/`Gauge`. | all |
| 1.2 | **Implement DLQs.** Declare `dlx.exchange` and `.dlq` queues. Add `x-dead-letter-exchange` args to existing queues. Switch listener error handling to `RejectAndDontRequeueRecoverer`. | gateway, processing |
| 1.3 | **Enable publisher confirms** on `RabbitTemplate`. Log unroutable messages. | simulator, gateway |
| 1.4 | **Add Resilience4j** circuit breakers around outbound calls (prep for Phase 2 service calls). Add retry annotations. | gateway |
| 1.5 | **Add Redis to Docker Compose** and integrate `spring-boot-starter-data-redis` + `spring-boot-starter-cache` into the gateway. Deploy Redis 7 Alpine with `maxmemory-policy allkeys-lru`. | gateway, infra |
| 1.6 | **Implement message deduplication** in the gateway. Use Redis `SET readingId EX 300 NX` as the first check in `SensorDataListener.handleSensorReading()` to discard duplicate readings before validation. Expose `gateway.readings.deduplicated` Micrometer counter. | gateway |
| 1.7 | **Implement per-device rate limiting** in the gateway. Use Redis `INCR`/`EXPIRE` sliding-window counters keyed by `ratelimit:{sensorId}:{minute}`. Reject readings exceeding configurable thresholds and route to DLQ. Expose `gateway.readings.rate_limited` counter. | gateway |
| 1.8 | **Cache anomaly thresholds in Redis.** Migrate `ThresholdConfig` values to Redis hashes (`config:thresholds:{sensorType}`). Fall back to YAML defaults on cache miss. Add admin endpoint `PUT /api/gateway/thresholds/{sensorType}` for runtime updates without redeployment. | gateway |
| 1.9 | **Structured JSON logging** with `logstash-logback-encoder`. Include `correlationId`, `traceId`, `service` in MDC. | all |
| 1.10 | **Distributed tracing** via Micrometer + OpenTelemetry bridge. Auto-instrument AMQP and REST. | all |
| 1.11 | **Externalize secrets.** Move RabbitMQ and Redis credentials to environment variables. Add `spring.config.import: optional:vault://` placeholder for Phase 4. | all |
| 1.12 | **Dockerize services.** Multi-stage `Dockerfile` per service (build with Maven, run with Eclipse Temurin JRE 25). Update `docker-compose.yml` to include all services + Redis + Prometheus + Grafana + Zipkin. | all |

**Deliverable:** All three services running in Docker Compose with health checks, metrics dashboards, tracing, DLQs, gateway Redis caching (dedup, rate limiting, dynamic thresholds), and structured logs.

---

### Phase 2 — Core New Services (Weeks 4–8)

*Goal: Add persistence and device management.*

> **Status: ✅ Complete (as built).** All steps below are implemented and the full reactor builds green across 7 modules. Where the implementation deviated from the original wording, the change is flagged inline with *(As built)*. See `HANDOFF.md` for the full as-built summary.

| Step | Action |
|---|---|
| 2.1 | **Deploy TimescaleDB** (PostgreSQL + extension) via Docker Compose. Create `sensor_readings` hypertable + 1-min continuous aggregate + 7-day compression policy. *(As built: coarser 15-min/1-hour/1-day rollups and time-based retention policies remain TODO.)* ✅ done |
| 2.2 | **Build Persistence/History Service** (port 8085). Spring Boot + Spring Data JPA. *(As built: binds its **own** queue `telemetry.persistence.queue` to `sensor.exchange` on `data.processed` — fan-out, persists every event independently of processing-service, **not** a competing consumer on `processed.data.queue`.)* Writes to the TimescaleDB hypertable. Exposes `/api/history` REST endpoints. ✅ done |
| 2.3 | **Build Device Registry Service** (port 8084). PostgreSQL `devices` table + Redis cache (Redis already deployed in Phase 1). REST API for CRUD + lifecycle + decommission. Gateway calls registry to validate `sensorId` on each reading (with circuit breaker fallback). ✅ done |
| 2.4 | **Enable device status caching in the gateway.** Use the Redis instance from Phase 1 to cache device status with 60s TTL. *(As built: caching and resilience are split across two beans — `DeviceRegistryClient` carries `@Cacheable("deviceStatus")` and `DeviceRegistryGateway` carries `@CircuitBreaker`/`@Retry` — so Spring AOP proxies both concerns on the hot path.)* Subscribe to `device.status.changed` pub/sub channel for instant cache invalidation on state changes. Expose `device.registry.cache.hit.ratio` Micrometer gauge. ✅ done |
| 2.5 | **Update sensor-simulator-service** to auto-register devices on startup (with scheduled retry) and send periodic `device.heartbeat` messages. ✅ done |
| 2.6 | **Update gateway-service** to call Device Registry (cached) during validation step. Reject readings from unknown/decommissioned devices → route to DLQ. ✅ done |
| 2.7 | **Add integration tests** using Testcontainers (RabbitMQ, PostgreSQL/TimescaleDB, Redis) for end-to-end flow validation; guarded with `disabledWithoutDocker` so the build stays green without a Docker daemon. ✅ done |

**Deliverable:** Persistent telemetry storage, device lifecycle management with Redis-cached lookups, historical query API, integration test suite.

---

### Phase 3 — Security & Notifications (Weeks 9–12)

*Goal: Lock down the platform and enable real-world alerting.*

| Step | Action |
|---|---|
| 3.1 | **Build IAM Service.** Spring Authorization Server. PostgreSQL user/role store. JWT issuance + JWKS endpoint. Device credential provisioning. |
| 3.2 | **Secure REST APIs.** Add `spring-boot-starter-oauth2-resource-server` to all services. `SecurityFilterChain` validates JWTs. `@PreAuthorize` on endpoints. |
| 3.3 | **Secure RabbitMQ.** Enable TLS on broker. Per-service credentials with topic permissions. Rotate credentials via Vault. |
| 3.4 | **Build Notification Service.** Consumes `alerts.queue`. Integrates with SendGrid (email), Twilio (SMS), FCM (push). Notification preferences in PostgreSQL. Deduplication with sliding window. |
| 3.5 | **Remove alert handling from processing-service.** Delete `AlertListener` and `AlertHandlerService`. Notification Service takes ownership of `alerts.queue`. |
| 3.6 | **Add Spring Cloud Gateway** as the single entry point for all REST traffic. JWT validation, rate limiting, request logging. |
| 3.7 | **Implement mTLS** for internal service-to-service REST calls. |

**Deliverable:** Fully secured platform with authentication, encrypted transport, multi-channel notifications.

---

### Phase 4 — Kubernetes & CI/CD (Weeks 13–16)

*Goal: Deploy to a production-grade orchestrated environment.*

| Step | Action |
|---|---|
| 4.1 | **Create Helm charts** for each service. ConfigMaps for `application.yml`, Secrets for credentials, HPA (Horizontal Pod Autoscaler) for processing-service and gateway. |
| 4.2 | **Deploy RabbitMQ** via the official RabbitMQ Cluster Operator (quorum queues for HA). |
| 4.3 | **Deploy TimescaleDB** via the TimescaleDB Helm chart or a managed PostgreSQL service. |
| 4.4 | **Deploy observability stack** — Prometheus Operator, Grafana, Loki, Tempo (or Zipkin) — via `kube-prometheus-stack` Helm chart. |
| 4.5 | **HashiCorp Vault** (or K8s-native Sealed Secrets) for secrets injection via init containers or CSI driver. |
| 4.6 | **CI/CD pipeline** (GitHub Actions or GitLab CI): lint → unit test → integration test (Testcontainers) → build Docker images → push to registry → Helm upgrade to staging → smoke tests → promote to production. |
| 4.7 | **Istio service mesh** (optional) for mTLS, traffic management, canary deployments. |
| 4.8 | **Load testing** with Gatling or k6: simulate 10K sensors at 1 reading/sec; validate throughput, latency p99, and auto-scaling behavior. |

**Deliverable:** Production Kubernetes deployment with automated CI/CD, secrets management, auto-scaling, and verified performance under load.

---

## Target Architecture Diagram

```
                         ┌──────────────────┐
                         │   API Gateway     │
                         │ (Spring Cloud GW) │
                         │  JWT · Rate Limit │
                         └────────┬─────────┘
                                  │ REST (mTLS)
         ┌────────────────────────┼────────────────────────┐
         │                        │                        │
  ┌──────▼──────┐  ┌──────────────▼──────────┐  ┌─────────▼─────────┐
  │ IAM Service │  │   Device Registry Svc   │  │  Notification Svc │
  │ OAuth2/JWT  │  │ PostgreSQL + Redis Cache │  │ Email/SMS/Push    │
  └─────────────┘  └─────────────────────────┘  └─────────▲─────────┘
                                                          │
     ┌──────────────┐       RabbitMQ (TLS)                │
     │   Sensor     │    ┌──────────────────┐             │
     │  Simulator   ├───►│ sensor.exchange  │             │
     │  (or real    │    │   (topic)        │             │
     │   devices)   │    └───┬──────────┬───┘             │
     └──────────────┘        │          │                 │
                             ▼          ▼                 │
                    ┌────────────┐ ┌──────────┐           │
                    │ readings Q │ │processed │           │
                    │ (+DLQ)     │ │  data Q  │           │
                    └─────┬──────┘ │ (+DLQ)   │           │
                          │        └────┬─────┘           │
                          ▼             ▼                 │
                   ┌─────────────┐ ┌──────────────┐       │
                   │  Gateway    │ │ Processing   │       │
                   │  Service    │ │ Service      │       │
                   │ Validate    │ │ Real-time    │       │
                   │ Enrich      │ │ Analytics    │       │
                   │ Anomaly Det │ └──────────────┘       │
                   └──────┬──────┘                        │
                          │ alerts.exchange                │
                          ▼                               │
                    ┌───────────┐                          │
                    │ alerts Q  ├──────────────────────────┘
                    │ (+DLQ)    │
                    └───────────┘

                   ┌──────────────────┐     ┌───────────────┐
                   │ Persistence/     │     │ Observability │
                   │ History Service  │     │ Prometheus    │
                   │ TimescaleDB      │     │ Grafana       │
                   │ (processed.data) │     │ Loki · Zipkin │
                   └──────────────────┘     └───────────────┘
```

---

## Summary of Key Technology Choices

| Concern | Choice | Rationale |
|---|---|---|
| Time-series storage | TimescaleDB | SQL-compatible, stays in Postgres ecosystem, compression, continuous aggregates |
| Relational data | PostgreSQL 16 | Proven, transactional, Spring Data JPA |
| Cache | Redis 7 | Fast lookups, TTL, pub/sub invalidation |
| Circuit breakers | Resilience4j | Spring Boot native integration, lightweight, annotation-driven |
| Auth | Spring Authorization Server + JWT | First-party Spring support, standards-compliant OAuth 2.1 |
| Tracing | Micrometer + OpenTelemetry + Zipkin | Auto-instruments Spring AMQP, zero-code propagation |
| Logging | Logback + Loki + Grafana | Structured JSON, lightweight vs. ELK, unified with metrics |
| Metrics | Micrometer + Prometheus + Grafana | De facto standard for K8s workloads |
| Orchestration | Kubernetes + Helm | Industry standard for microservices at scale |
| CI/CD | GitHub Actions | Native to most Git workflows, Testcontainers support |
| Secrets | HashiCorp Vault | Dynamic secrets, auto-rotation, K8s integration |
