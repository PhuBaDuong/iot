# IoT Smart Home Monitor — Project State

**Last updated:** 2026-06-25
**Status:** Phases 1, 2, 3A, and 3B complete; Notification Service built and alert handling migrated. Full Maven reactor (8 modules) builds green; all unit/security tests pass (26 tests across 6 security test classes) and Docker-dependent integration tests skip cleanly when no daemon is present.

---

## 1. Overview & Technology Stack

A microservices-based IoT monitoring system that ingests synthetic sensor telemetry, validates and enriches it, runs analytics and anomaly detection, persists it durably, tracks device lifecycle, and is secured end-to-end with OAuth 2.1 / OIDC.

| Concern | Choice |
|---|---|
| Language / runtime | Java 25 |
| Framework | Spring Boot 4.0.1 |
| Security | Spring Security 7.1 (Authorization Server is a core component) |
| Cloud / resilience | Spring Cloud 2025.1.2 (Oakwood) — Resilience4j circuit breakers/retry |
| Build | Maven multi-module reactor (`./mvnw`) |
| Messaging | RabbitMQ (topic exchange + per-queue DLQ) |
| Cache / fast state | Redis (dedup, rate-limit, thresholds, device-status cache, pub/sub) |
| Time-series store | TimescaleDB (Postgres 16) |
| Relational store | Postgres (device registry + IAM, same TimescaleDB instance) |
| Observability | Micrometer + Prometheus + Grafana; OpenTelemetry → Zipkin tracing; structured JSON logging (Logstash encoder) |

**Group/version:** `com.smarthome:iot-parent:1.0.0-SNAPSHOT`

---

## 2. Module Map (Maven Reactor)

Eight modules declared in the root `pom.xml`:

| Module | Port | Purpose |
|---|---|---|
| `iot-common` | — | Shared DTOs/events, `RabbitMQConstants`, `JwtAuthConverterFactory` |
| `sensor-simulator-service` | 8081 | Publishes synthetic readings + device heartbeats |
| `gateway-service` | 8082 | Validate, dedup, rate-limit, anomaly-detect, route |
| `processing-service` | 8083 | Analytics (in-memory statistics) |
| `device-registry-service` | 8084 | Device metadata + lifecycle, heartbeat consumer |
| `history-service` | 8085 | Durable telemetry persistence to TimescaleDB + query API |
| `iam-service` | 9000 | OAuth 2.1 / OIDC Authorization Server (issues & signs JWTs) |
| `notification-service` | 8086 | Alert notification dispatch, preferences, dedup, audit log |

---

## 3. Roadmap Status

The project follows `production_plan.md` (with `auth_plan.md` driving Phase 3).

- ✅ **Phase 1 — Resilience & Observability:** Resilience4j circuit breakers/retry, Redis-backed rate limiting & deduplication, dead-letter topology, structured JSON logging, Prometheus/Grafana/Zipkin.
- ✅ **Phase 2 — Data Persistence & Device Management:** TimescaleDB telemetry hypertable + 1-min continuous aggregate, `device-registry-service` (lifecycle + heartbeats), `history-service` (durable storage + query API), fan-out persistence queue.
- ✅ **Phase 3A — IAM Service & Resource-Server Security:** Spring Authorization Server, shared JWT role converter, three resource servers hardened (gateway, processing, simulator), Docker/Prometheus wiring, security tests.
- ✅ **Phase 3B — Secure remaining services:** `device-registry-service` and `history-service` hardened as OAuth2 resource servers with RBAC; docker-compose wired with `IAM_ISSUER` and `iam-service` dependency; security tests (RegistrySecurityTest 8/8, HistorySecurityTest 3/3).
- ✅ **Phase 3.4/3.5 — Notification Service & Alert Migration:** New `notification-service` module (port 8086) consuming `alerts.queue`; JPA entities for preferences and notification records; channel abstraction (email/SMS/webhook stubs); dedup and severity filtering; REST API for history and preferences; alert handling removed from `processing-service` (`AlertListener` + `AlertHandlerService` deleted).
- ⏳ **Phase 3C — RabbitMQ TLS/mTLS + HMAC message signing:** not started.
- ⏳ **Phase 3.6 — Spring Cloud Gateway:** edge proxy not started.
- ⏳ **Phase 3.7 — mTLS:** internal service-to-service mTLS not started.

---

## 4. Infrastructure (`docker-compose.yml`)

One stack brings up all backing services plus the eight app services. Internal hostnames equal the compose service names.

| Service | Image | Host port(s) | Notes |
|---|---|---|---|
| rabbitmq | `rabbitmq:3.12-management` | 5672, 15672 | user/pass `smarthome` / `smarthome123` |
| redis | `redis:7-alpine` | 6379 | dedup / rate-limit / thresholds / cache |
| timescaledb | `timescale/timescaledb:2.17.2-pg16` | 5432 | hosts `telemetry`, `devices`, `smarthome_iam`, `notifications` DBs |
| zipkin | `openzipkin/zipkin:latest` | 9411 | trace collector |
| prometheus | `prom/prometheus:latest` | 9090 | scrapes `/actuator/prometheus` |
| grafana | `grafana/grafana:latest` | 3000 | admin/admin, Prometheus datasource provisioned |
| gateway-service | built | 8082 | RabbitMQ, Redis, `DEVICE_REGISTRY_URL`, `IAM_ISSUER` |
| processing-service | built | 8083 | RabbitMQ, `IAM_ISSUER` |
| device-registry-service | built | 8084 | RabbitMQ, Redis, DB `devices`, `IAM_ISSUER` |
| history-service | built | 8085 | RabbitMQ, DB `telemetry`, `IAM_ISSUER` |
| iam-service | built | 9000 | DB `smarthome_iam`, `IAM_ISSUER=http://iam-service:9000` |
| notification-service | built | 8086 | RabbitMQ, DB `notifications`, `IAM_ISSUER` |
| sensor-simulator-service | built | 8081 | RabbitMQ, `DEVICE_REGISTRY_URL`, `IAM_ISSUER` |

**Common env wiring:** `RABBITMQ_HOST/PORT/USERNAME/PASSWORD`, `REDIS_HOST/PORT`, `DB_HOST/PORT/NAME/USERNAME/PASSWORD`, `IAM_ISSUER`, `ZIPKIN_ENDPOINT=http://zipkin:9411/api/v2/spans`. All app services expose `/actuator/health` healthchecks with a 60s start period. All resource servers depend on `iam-service: condition: service_healthy`. Named volumes persist RabbitMQ, Redis, TimescaleDB, Prometheus, and Grafana data.

---

## 5. Datastores & Schemas

### TimescaleDB (single Postgres 16 instance, three databases)
Init scripts in `db/timescaledb/init/` (`01-create-databases.sql`, `02-telemetry-schema.sql`, `03-aggregates.sql`). Four databases created on first startup.

**`telemetry` DB — history-service** (`ddl-auto: none`, schema pre-created):
- Hypertable `sensor_readings(time TIMESTAMPTZ, reading_id, sensor_id, sensor_type, value DOUBLE PRECISION, unit, location, correlation_id, anomaly BOOLEAN, anomaly_description)`; partitioned on `time`.
- Index `(sensor_id, time DESC)` for "latest N per sensor".
- Compression enabled for chunks older than 7 days.
- Continuous aggregate `sensor_readings_1min` (avg/min/max/count per 1-minute bucket).

**`devices` DB — device-registry-service** (`ddl-auto: update`, Hibernate-managed):
- `DeviceEntity`: `sensorId` (PK), `deviceName`, `sensorType`, `location`, `firmwareVersion`, `registeredBy`, `status` (PROVISIONED→ACTIVE→INACTIVE→DECOMMISSIONED), `lastSeenAt`.

**`smarthome_iam` DB — iam-service** (`ddl-auto: validate`):
- `users(id, username UNIQUE, email UNIQUE, password_hash bcrypt, enabled, account_locked, created_at)`
- `roles(id, name UNIQUE, description)` — seeded ADMIN, OPERATOR, VIEWER
- `user_roles(user_id, role_id)` join table
- Seeded bootstrap admin (configurable via env) with ADMIN role.

**`notifications` DB — notification-service** (`ddl-auto: update`, Hibernate-managed):
- `notification_preferences`: per-user delivery config — `userId` (unique), email/SMS/webhook toggles and addresses, `minSeverity` (INFO/WARNING/CRITICAL), quiet hours, timestamps.
- `notification_records`: audit log — `alertId`, `sensorId`, `channel` (EMAIL/SMS/WEBHOOK/PUSH), `status` (SENT/FAILED/SUPPRESSED), `severity`, `message`, `recipient`, `failureReason`, `sentAt`, `correlationId`. Indexed on `alert_id`, `sensor_id`, `sent_at`.

### Redis (gateway-service primarily)
- **Deduplication:** `dedup:{readingId}` via SET NX, TTL 300s.
- **Rate limiting:** `ratelimit:{sensorId}:{minuteEpoch}` fixed-window INCR, TTL ~120s, default 120/min.
- **Threshold overrides:** hash `config:thresholds:{SENSORTYPE}` with `min`/`max`; falls back to YAML defaults.
- **Device-status cache:** Spring Cache (`deviceStatus`), ~60s TTL.
- **Pub/Sub channel `device.status.changed`:** device-registry publishes sensorId on lifecycle changes; gateway evicts its device-status cache entry.

---

## 6. RabbitMQ Topology

All names are centralized in `iot-common` `RabbitMQConstants`.

**Exchanges:** `sensor.exchange` (topic, primary bus), `alerts.exchange` (topic), `dlx.exchange` (direct, dead-letters).

| Queue | Bound routing key | Consumer(s) |
|---|---|---|
| `sensor.readings.queue` | `sensor.#` | gateway-service |
| `processed.data.queue` | `data.processed` | processing-service |
| `telemetry.persistence.queue` | `data.processed` | history-service |
| `alerts.queue` | `alert.anomaly` | notification-service |
| `device.heartbeat.queue` | `device.heartbeat` | device-registry-service |

**Fan-out note:** `processed.data.queue` and `telemetry.persistence.queue` both bind `data.processed` on the topic exchange, so every processed event is delivered to *both* — processing runs analytics while history persists. This is fan-out, not competing consumers.

**Routing keys:** readings published as `sensor.{type}.{location}` (matches `sensor.#`); heartbeats as `device.heartbeat` (deliberately does NOT match `sensor.#`); processed data `data.processed`; alerts `alert.anomaly`.

**Dead-letter topology:** each main queue declares `x-dead-letter-exchange=dlx.exchange` with a dedicated DLQ — `sensor.readings.dlq`, `processed.data.dlq`, `alerts.dlq`, `device.heartbeat.dlq`, `telemetry.persistence.dlq`. `sensor.readings.queue` also has a 5-minute (`300000` ms) message TTL. Gateway's `DlqMonitorService` exposes DLQ depth as a Prometheus gauge.

---

## 7. Security Model (Phase 3A + 3B)

### IAM service — Authorization Server
- Spring Authorization Server exposes `/oauth2/token`, `/oauth2/authorize`, `/oauth2/jwks`, `/userinfo`, `/.well-known/oauth-authorization-server`.
- RSA `JWKSource`; JWTs are RS256-signed. An `OAuth2TokenCustomizer` adds custom `roles`, `username`, and `email` claims.
- **Registered clients:** `smarthome-dashboard` (public SPA — Authorization Code + PKCE + refresh; redirect URIs on localhost:5173/3000) and three confidential service clients (`gateway-service`, `processing-service`, `sensor-simulator-service` — client_credentials, scope `service:internal`, dev secret overridable via env).
- Form-login chain for browser auth; `@EnableMethodSecurity`; CORS for dashboard origins. `DataInitializer` idempotently seeds roles + bootstrap admin.

### Shared role mapping — `iot-common`
- `JwtAuthConverterFactory` maps the JWT `roles` claim → Spring Security `ROLE_*` authorities. Added as *optional* security deps so non-secured modules stay lean. (Spring Security 7 also adds a default `FACTOR_BEARER` authority alongside the `ROLE_*` ones.)

### Resource servers — all six app services
Each validates JWTs against the IAM JWKS endpoint via `jwk-set-uri` (lazy fetch → no hard startup dependency on IAM; fail-open philosophy), is stateless, disables CSRF, and leaves actuator health/metrics public.

**RBAC (roles: ADMIN, OPERATOR, VIEWER):**
- `gateway`: `GET /api/gateway/**` → ADMIN/OPERATOR/VIEWER; `PUT /api/gateway/thresholds/**` → ADMIN only.
- `processing`: `GET /api/analytics/**` → ADMIN/OPERATOR/VIEWER.
- `simulator`: `GET /api/simulator/status` → any role; `POST /api/simulator/{start,stop,trigger}` → ADMIN/OPERATOR.
- `device-registry`: `GET /api/devices/**` → any role; `POST /api/devices` → ADMIN/OPERATOR; `PUT /api/devices/*/status`, `POST /api/devices/*/decommission`, `DELETE /api/devices/**` → ADMIN only.
- `history`: `GET /api/history/**` → any role.
- `notification`: `GET /api/notifications/**` → any role; `POST/PUT /api/notifications/preferences/**` → ADMIN/OPERATOR; `DELETE /api/notifications/preferences/**` → ADMIN only.
- `iam`: `POST /api/users/register` public; `PUT /api/users/{id}/roles` → ADMIN.

---

## 8. End-to-End Data Flow

1. **Simulator** publishes `SensorReading` to `sensor.exchange` with key `sensor.{type}.{location}` (every ~2s), and `HeartbeatEvent` with key `device.heartbeat` (every ~15s).
2. **Gateway** consumes `sensor.readings.queue` → validate → dedup (Redis) → rate-limit (Redis) → device check (cached) → anomaly detection (Redis/YAML thresholds). Emits `SensorDataEvent` (`data.processed`) and, on anomaly, `AlertEvent` (`alert.anomaly`).
3. **Processing** consumes `processed.data.queue` (updates in-memory statistics) — served via `/api/analytics/**`.
4. **Notification** consumes `alerts.queue`, deduplicates by `alertId`, checks severity thresholds against user preferences, and dispatches through configured channels (email/SMS/webhook stubs). Records are persisted to the `notifications` database for audit. Served via `/api/notifications/**`.
5. **History** consumes `telemetry.persistence.queue` and inserts into the TimescaleDB hypertable; duplicate `reading_id` is treated idempotently. Served via `/api/history/**`.
6. **Device registry** consumes `device.heartbeat.queue`, updates `lastSeenAt`, promotes to ACTIVE, and a scheduled sweep flips silent devices to INACTIVE (publishing `device.status.changed` to Redis for gateway cache eviction).
7. Failures after retry exhaustion dead-letter through `dlx.exchange` to per-queue DLQs.

---

## 9. Observability

- **Metrics:** every service exposes `/actuator/prometheus`; Prometheus scrapes all seven app ports (8081–8086, 9000). Custom counters include `sensor.readings.published`, `sensor.heartbeats.published`, `gateway.readings.deduplicated`, `gateway.readings.rate_limited`, `gateway.anomalies.detected`, `rabbitmq.queue.depth` (gauge), `registry.heartbeats.received`, `notification.alerts.handled`, `notifications.sent`, `notifications.failed`, `notifications.alerts.received`, `notifications.alerts.deduplicated`.
- **Tracing:** Micrometer Tracing → OpenTelemetry → Zipkin (`http://zipkin:9411`); `correlationId` carried on events.
- **Logging:** structured JSON via Logstash Logback encoder across all services.

---

## 10. Test Coverage

| Module | Tests |
|---|---|
| gateway-service | `JwtAuthConverterFactoryTest` (role mapping), `GatewaySecurityTest` (`@WebMvcTest` 401/403/200), `DeduplicationServiceTest`, `RateLimitingServiceTest`, `ThresholdServiceTest` |
| processing-service | `ProcessingSecurityTest` (`@WebMvcTest` RBAC) |
| sensor-simulator-service | `SimulatorSecurityTest` (`@WebMvcTest` RBAC) |
| device-registry-service | `RegistrySecurityTest` (`@WebMvcTest` RBAC 8 tests: 401/403/200/201/204), `DeviceRegistryIntegrationTest` (Testcontainers: register → heartbeat → sweep → decommission) |
| history-service | `HistorySecurityTest` (`@WebMvcTest` RBAC 3 tests: 401/200), `HistoryPersistenceIntegrationTest` (Testcontainers: publish → persist → query) |
| iam-service | `IamServiceIntegrationTest` (Testcontainers: JWKS published + client_credentials token), using `RestClient` |

All `@WebMvcTest` slices use `spring-boot-starter-webmvc-test` (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`). Testcontainers tests are guarded with `@Testcontainers(disabledWithoutDocker = true)` and skip cleanly when no Docker daemon is available.

---

## 11. Build & Run

```bash
# Build everything + run all tests (8-module reactor)
./mvnw clean test

# Package
./mvnw clean package

# Full stack (backing services + all app services)
docker compose up --build
```

Useful endpoints once up: RabbitMQ UI `:15672`, Prometheus `:9090`, Grafana `:3000`, Zipkin `:9411`, IAM JWKS `:9000/oauth2/jwks`, Notification API `:8086/api/notifications/history`.

---

## 12. Notable Spring Boot 4 / Security 7 Adaptations

- `OAuth2AuthorizationServerConfiguration.applyDefaultSecurity()` was removed → configured via the `http.oauth2AuthorizationServer(...)` DSL with Boot auto-configuring the `JwtDecoder` from the `JWKSource`.
- `@WebMvcTest` relocated to `spring-boot-starter-webmvc-test`.
- Resource servers use `jwk-set-uri` (lazy validation) to avoid a hard startup dependency on IAM.
- Tests use `RestClient` instead of `TestRestTemplate` to avoid test-module relocation uncertainty.
- Spring Security 7's `PathPatternParser` rejects `**` in the middle of patterns → use `*` for single path segments (e.g., `/api/devices/*/status` not `/api/devices/**/status`).

---

## 13. Next Steps (pending)

1. **Phase 3C** — RabbitMQ TLS/mTLS + per-service credentials with topic permissions.
2. **Phase 3.6** — Spring Cloud Gateway as edge proxy (JWT validation, rate limiting, request logging).
3. **Phase 3.7** — mTLS for internal service-to-service communication.
4. **Phase 4** — Production Hardening (Kubernetes/Helm, Vault secrets, CI/CD, load testing).

---

## 14. Reference Documents

- `production_plan.md` — overall architectural roadmap (reconciled with as-built reality).
- `auth_plan.md` — detailed Phase 3 security plan (3A/3B/3C).
- `AUTH_IMPLEMENTATION.md` — deep dive into the authentication/authorization mechanism.
- `ARCHITECTURE_WALKTHROUGH.md`, `SERVICE_GUIDE.md`, `HANDOFF.md`, `frontend_plan.md`.
