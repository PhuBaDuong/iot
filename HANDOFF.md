x`# Project Handoff — IoT Smart Home Monitor

Status snapshot for the production-readiness effort tracked in `production_plan.md`.
**Phases 1, 2, 3A, and 3B are complete and tested. Phase 3 partially remains (3C, 3.6, 3.7). Phase 4 is not started.**

- **Stack:** Spring Boot 4.0.1, Java 25, Spring Cloud 2025.1.2 (Oakwood), Maven multi-module reactor.
- **Modules:** `iot-common`, `sensor-simulator-service` (8081), `gateway-service` (8082), `processing-service` (8083), `device-registry-service` (8084), `history-service` (8085), `notification-service` (8086), `iam-service` (9000).
- **Datastores:** RabbitMQ, Redis, TimescaleDB (`telemetry`, `devices`, `notifications` databases), PostgreSQL (`smarthome_iam` database).
- **Build:** `./mvnw clean package` (full reactor, 9 modules) — green. `./mvnw clean test` — unit tests green; Testcontainers integration tests run when Docker is available, otherwise skip cleanly.
- **Run locally:** `docker compose up --build` brings up the full stack (RabbitMQ, Redis, TimescaleDB, Zipkin, Prometheus, Grafana + 7 app services including IAM and notification).

---

## ✅ Done — Phase 1: Foundation & Resilience

All 12 sub-tasks complete:

1. **Actuator + Prometheus metrics** — added to all 3 services; `/actuator/health` and `/actuator/prometheus` exposed; Micrometer counters replace ad-hoc `AtomicLong`s.
2. **Dead Letter Queues** — `dlx.exchange` (direct) + `.dlq` queues; `x-dead-letter-exchange` args on main queues; `RejectAndDontRequeueRecoverer`; `DlqMonitorService`.
3. **Publisher confirms** — `publisher-confirm-type: correlated` + `publisher-returns` on simulator + gateway; unroutable messages logged.
4. **Resilience4j** — `spring-cloud-starter-circuitbreaker-resilience4j` on gateway; `OutboundPublisher` bean wraps RabbitMQ sends with `@CircuitBreaker`/`@Retry` (separate bean so Spring AOP proxies the calls).
5. **Redis integration** — Redis in compose; `spring-boot-starter-data-redis` + `-cache` on gateway; `RedisConfig` (connection + `RedisCacheManager`, `deviceStatus` cache stub for Phase 2).
6. **Deduplication** — `DeduplicationService` (Redis `SET NX` + TTL); first check in `SensorDataListener`; duplicates discarded silently; metric `gateway.readings.deduplicated`.
7. **Per-device rate limiting** — `RateLimitingService` (Redis `INCR`/`EXPIRE` fixed-window, key `ratelimit:{sensorId}:{minute}`); over-limit → `AmqpRejectAndDontRequeueException` → DLQ; metric `gateway.readings.rate_limited`.
8. **Dynamic thresholds** — `ThresholdService` reads Redis hash `config:thresholds:{sensorType}` with YAML fallback; admin endpoint `PUT /api/gateway/thresholds/{sensorType}`.
9. **Structured JSON logging** — `logstash-logback-encoder`; `logback-spring.xml` per service; MDC carries `service`, `traceId`, `spanId`, `correlationId`.
10. **Distributed tracing** — `micrometer-tracing-bridge-otel` + Zipkin exporter on all services; AMQP + REST auto-instrumented.
11. **Externalized secrets** — RabbitMQ/Redis creds via env vars in `application.yml`; Vault import placeholder left commented.
12. **Dockerize + observability stack** — multi-stage `Dockerfile` per service (Maven JDK 25 build → `eclipse-temurin:25-jre` runtime, non-root, healthcheck-ready); `.dockerignore`; `monitoring/prometheus.yml` + Grafana datasource provisioning; `docker-compose.yml` includes RabbitMQ, Redis, Zipkin, Prometheus, Grafana + the 3 app services with env wiring, health checks, and dependency ordering.

### Tests added (most recent session)
`gateway-service/src/test/java/com/smarthome/gateway/service/`
- `DeduplicationServiceTest` (4), `RateLimitingServiceTest` (5), `ThresholdServiceTest` (5) — Mockito-mocked `StringRedisTemplate`, real `SimpleMeterRegistry`. **14 tests passing.** These are the only unit tests in the repo.

---

## ✅ Done — Phase 2: Data Persistence & Device Management

All sub-tasks complete; full reactor builds green across **7 modules**.

- **2.0 Shared foundation** — `iot-common`: device/heartbeat/persistence `RabbitMQConstants`, `RedisConstants`, shared DTOs/events (`DeviceStatus`, `DeviceDto`, `DeviceRegistrationRequest`, `HeartbeatEvent`). TimescaleDB added to compose with init SQL (`devices` DB, `sensor_readings` hypertable, 1-min continuous aggregate, 7-day compression). `testcontainers-bom` (1.21.3) added to the root pom's `dependencyManagement`.
- **2.1 Device Registry Service (8084)** — new module: JPA `DeviceEntity`/repo, `DeviceService` (idempotent upsert, lifecycle transitions, Redis `device.status.changed` publish), `HeartbeatListener` (RabbitMQ), `HeartbeatMonitor` (scheduled inactivity sweep → `INACTIVE` after the silence window), REST CRUD + decommission, RFC-7807 error handling.
- **2.2 History Service (8085)** — new module: `SensorReadingEntity` (implements `Persistable` to force append-only inserts on the hypertable), its **own** queue (`telemetry.persistence.queue`) fanned out from the topic exchange on `data.processed` (persists every event independently of processing-service), `PersistenceService`, `/api/history` query API.
- **2.3 Gateway validation + caching** — `DeviceRegistryGateway` (`@CircuitBreaker`/`@Retry` REST, fail-open `UNVERIFIED`) + `DeviceRegistryClient` (`@Cacheable` 60s) using the two-bean AOP pattern; `SensorDataListener` rejects unknown/decommissioned devices to the DLQ; Redis pub/sub eviction listener keeps the cache fresh.
- **2.4 Simulator** — auto-registers every sensor on startup (with scheduled retry, tolerating registry-not-up-yet) and publishes periodic `device.heartbeat` messages.
- **2.5 Compose + Prometheus** — both new services wired with DB/Redis/RabbitMQ/Zipkin env, health checks, dependency ordering; gateway/simulator get `DEVICE_REGISTRY_URL`; Prometheus scrapes ports 8084/8085. `docker compose config` validates.
- **2.6 Integration tests** — `DeviceRegistryIntegrationTest` (Postgres + RabbitMQ + Redis) and `HistoryPersistenceIntegrationTest` (TimescaleDB + RabbitMQ, end-to-end publish→persist), both guarded with `@Testcontainers(disabledWithoutDocker = true)` so the build stays green without a Docker daemon.

---

## ✅ Done — Phase 3A: IAM Service & Resource Server Security

All services secured as OAuth2 resource servers with role-based access control.

- **IAM Service (port 9000)** — Spring Authorization Server with OAuth 2.1/OIDC. PostgreSQL `smarthome_iam` database with `users`, `roles`, `oauth2_registered_client` tables. RS256 JWT issuance with custom `roles`/`username`/`email` claims. JWKS endpoint at `/oauth2/jwks`. Admin REST API (`/api/users/**`) for user CRUD. Bootstrap admin user seeded on startup. Client-credentials and authorization-code flows supported.
- **Shared `JwtAuthConverterFactory`** in `iot-common` — maps the custom `roles` JWT claim to `ROLE_*` Spring Security authorities. Used by all resource servers for consistent `hasRole(...)` evaluation.
- **Resource server wiring** — `spring-boot-starter-oauth2-resource-server` added to gateway, processing, simulator, device-registry, and history services. Each has a `SecurityConfig` with `SecurityFilterChain` validating JWTs against the IAM JWKS endpoint (`IAM_ISSUER` env var). RBAC rules per service (actuator endpoints permit-all, role-gated REST endpoints).
- **docker-compose** — IAM service added with `smarthome_iam` database, all services wired with `IAM_ISSUER` env and `iam-service` dependency.

---

## ✅ Done — Phase 3B: Device Registry & History Service Security

Both data services secured as OAuth2 resource servers with RBAC.

- **device-registry-service** — `SecurityConfig` with RBAC: `GET` endpoints accessible to any authenticated role (`ADMIN`, `OPERATOR`, `VIEWER`); `POST` restricted to `ADMIN`/`OPERATOR`; `PUT`/`DELETE`/decommission restricted to `ADMIN` only. All 8 `RegistrySecurityTest` tests pass.
- **history-service** — `SecurityConfig` with RBAC: `GET` endpoints accessible to any authenticated role. All 3 `HistorySecurityTest` tests pass.
- **docker-compose** — `IAM_ISSUER` env var and `iam-service` dependency added for both services.

---

## ✅ Done — Phase 3.4/3.5: Notification Service

New `notification-service` module replaces alert handling previously in `processing-service`.

- **Module** — Spring Boot service on port 8086, consuming from `alerts.queue` via RabbitMQ.
- **JPA entities** — `NotificationPreference` (per-user delivery channel preferences with severity threshold filtering, quiet hours, sensor filters) and `NotificationRecord` (audit log of all dispatched notifications with delivery status tracking).
- **Channel abstraction** — `NotificationChannel` interface with `EmailChannel`, `SmsChannel`, and `WebhookChannel` stub implementations (log-only; ready for SendGrid/Twilio/HTTP integration).
- **`AlertNotificationService`** — core dispatch logic: sliding-window deduplication by `(sensorId, sensorType, severity)`, severity threshold filtering per user preference, multi-user fan-out across configured channels.
- **REST API** — `/api/notifications/history/**` (query notification records) and `/api/notifications/preferences/**` (full CRUD for per-user notification preferences).
- **Security** — Secured as OAuth2 resource server with RBAC via `JwtAuthConverterFactory`.
- **Alert migration** — `AlertListener` and `AlertHandlerService` removed from `processing-service`; notification-service now owns `alerts.queue`.
- **Infrastructure** — Dockerfile, docker-compose wiring (RabbitMQ, TimescaleDB, IAM dependencies), and `notifications` database added to TimescaleDB init script.

---

## ✅ Done — Phase 3C: Secure RabbitMQ

TLS encryption and per-service least-privilege credentials on RabbitMQ.

- **TLS listener** — RabbitMQ 3.13 configured with TLS on port 5671 (TLSv1.2/1.3); self-signed CA + server cert generated by `certs/generate-certs.sh`; PKCS12 truststore for Spring Boot clients.
- **Per-service credentials** — 7 users defined in `rabbitmq/definitions.json` (admin + 6 service accounts); each service account has least-privilege permissions (configure/write/read) scoped to only the exchanges and queues it needs.
- **Full topology in definitions** — All exchanges, queues (with DLX args), and bindings pre-declared in `definitions.json`, imported on broker startup.
- **Spring Boot SSL** — All 6 RabbitMQ-using services have `spring.rabbitmq.ssl.*` config defaulting to `false` (local dev uses plain AMQP on 5672); Docker Compose sets `RABBITMQ_SSL_ENABLED=true` + port 5671.
- **Docker Compose** — Each service gets its own RabbitMQ username/password, SSL env vars, and `./certs:/certs:ro` volume mount.

---

## ⏳ Left to do

### Phase 3 — remaining items
- **3.6: Spring Cloud Gateway** — single entry point for all REST traffic; JWT validation, rate limiting, request logging.
- **3.7: mTLS** — mutual TLS for internal service-to-service REST calls.

### Phase 4 — Kubernetes & CI/CD
- Helm charts; RabbitMQ operator; TimescaleDB chart; observability stack.
- Vault for secrets; CI/CD pipeline; Istio (optional); load testing.

### Also pending (from the plan, not yet started)
- **Frontend dashboard** — `production_plan.md` calls for a React/Next.js (or Vite) dashboard: real-time monitoring (SSE/WebSocket from `processing-service`), historical analytics (TimescaleDB), device management UI, alert center, JWT auth via IAM, routed through Spring Cloud Gateway. A streaming endpoint still needs to be added to `processing-service`. Likely lives in a new `dashboard/` dir outside the Maven reactor. Depends on Phase 3 backend work completion.

---

## Key decisions / gotchas
- **Spring Cloud train:** `2025.1.2` (Oakwood) is required for Boot 4.0.x.
- **AOP proxying:** Resilience4j annotations must live on a *separate* bean (`OutboundPublisher`) — self-invocation inside `SensorDataListener` would bypass the proxy.
- **Fail-open design:** dedup, rate-limit, and threshold lookups all fall back gracefully if Redis is down (logged as WARN) so a Redis outage never drops live traffic.
- **Reactor install:** if a single-module goal complains about a missing `iot-common` artifact, run a full `./mvnw package` first to install it to the local repo.
- **YAML:** keep a single top-level `gateway:` key in `application.yml` (Redis/feature config and thresholds are merged under it).
- **Fan-out persistence (Phase 2):** history-service binds its *own* queue (`telemetry.persistence.queue`) to the topic exchange on `data.processed`, so it persists every event independently of processing-service's analytics — it is **not** a competing consumer on `processed.data.queue`. This deviates from the original plan wording; `production_plan.md` has been updated to match.
- **Two-bean registry client (Phase 2):** the gateway splits the device lookup across `DeviceRegistryGateway` (`@CircuitBreaker`/`@Retry`) and `DeviceRegistryClient` (`@Cacheable`) for the same AOP-proxy reason as `OutboundPublisher`.
- **Append-only hypertable:** `SensorReadingEntity` implements `Persistable` (`isNew()` always `true`) so Hibernate always issues INSERTs against the TimescaleDB hypertable (no SELECT-before-INSERT).
- **JWT role mapping (Phase 3A):** IAM puts roles in a custom `roles` claim (not standard `scope`/`scp`). The shared `JwtAuthConverterFactory` in `iot-common` maps these to `ROLE_*` authorities so `hasRole(...)` works consistently across all resource servers.
- **Notification dedup (Phase 3.4):** sliding-window dedup by `(sensorId, sensorType, severity)` tuple prevents alert storms from flooding notification channels. Cooldown period is configurable.

## Reference docs
- `production_plan.md` — authoritative 4-phase roadmap.
- `ARCHITECTURE_WALKTHROUGH.md`, `SERVICE_GUIDE.md` — design + per-service deep dives.
- `AUTH_IMPLEMENTATION.md` — Phase 3A IAM and security implementation details.
