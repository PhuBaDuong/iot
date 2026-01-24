# Session Handoff — IoT Smart Home Monitor

## Project Location

`/Users/techd/workspace/iot`

## What This Project Is

A Spring Boot 4.0.1 / Java 25 event-driven microservices platform for real-time IoT sensor monitoring. Uses RabbitMQ topic exchanges for async communication. Currently an educational prototype — no database, no auth, no real alerting.

## Current Codebase (unchanged — no code edits were made)

```
iot/
├── iot-common/                    Shared DTOs, events, constants (SensorReading, SensorDataEvent, AlertEvent, SensorType, RabbitMQConstants)
├── sensor-simulator-service/      Port 8081 — generates 7 synthetic sensors on 2s schedule, publishes to sensor.exchange
├── gateway-service/               Port 8082 — validates, detects anomalies (configurable thresholds), enriches, routes to processed.data.queue + alerts.queue
├── processing-service/            Port 8083 — in-memory analytics (ConcurrentHashMap), alert handler (log-only), REST API for stats/alerts
├── docker-compose.yml             RabbitMQ 3.12-management only
├── pom.xml                        Maven multi-module parent
├── production_plan.md             ✅ CREATED — comprehensive production roadmap (466 lines)
├── ARCHITECTURE_WALKTHROUGH.md    Original project docs
└── SERVICE_GUIDE.md               Original project docs
```

### Existing REST API Endpoints

| Service | Endpoint | Method | Purpose |
|---------|----------|--------|---------|
| simulator | `/api/simulator/status` | GET | Running status, interval, sensor list |
| simulator | `/api/simulator/trigger` | POST | Manually trigger one reading cycle |
| simulator | `/api/simulator/start` | POST | Start automatic simulation |
| simulator | `/api/simulator/stop` | POST | Stop automatic simulation |
| gateway | `/api/gateway/health` | GET | Health check with timestamp |
| gateway | `/api/gateway/stats` | GET | Processing stats (received, processed, failures, anomalies, rates) |
| processing | `/api/analytics/statistics` | GET | All sensor statistics (min/max/avg/count per sensor) |
| processing | `/api/analytics/statistics/{sensorId}` | GET | Single sensor stats |
| processing | `/api/analytics/alerts?limit=50` | GET | Recent AlertEvents |
| processing | `/api/analytics/health` | GET | Health check with metrics |
| processing | `/api/analytics/summary` | GET | Combined stats + alerts + counts |

### RabbitMQ Topology

- `sensor.exchange` (topic) → `sensor.readings.queue` (binding: `sensor.#`) → gateway consumes
- `sensor.exchange` (topic) → `processed.data.queue` (binding: `data.processed`) → processing consumes
- `alerts.exchange` (topic) → `alerts.queue` (binding: `alert.anomaly`) → processing consumes
- Credentials: `smarthome` / `smarthome123`, ports 5672 (AMQP) + 15672 (management UI)

### Key Gaps (by design — educational project)

- All analytics in-memory (no database)
- No authentication or authorization
- No TLS/SSL on AMQP or HTTP
- No Dead Letter Queues — failed messages after retries are lost
- No circuit breakers
- No message deduplication or idempotency
- Alerts are console-logged only (not sent anywhere)
- No distributed tracing or centralized logging
- No Docker images for the Java services (only RabbitMQ in compose)

## What Was Accomplished This Session

### 1. Full Codebase Analysis

Deep-read every Java source file (23 classes), all configs, docker-compose, and docs. Mapped the complete message flow: Simulator → sensor.exchange → gateway (validate → enrich → anomaly detect) → processed.data.queue + alerts.queue → processing-service (in-memory stats + log alerts).

### 2. Created `production_plan.md` (466 lines)

Comprehensive production-readiness roadmap covering:

- **Section 1 — Missing Microservices:** IAM Service (OAuth 2.1, JWT, Spring Authorization Server), Device Registry Service (lifecycle state machine, PostgreSQL + Redis), Notification Service (email/SMS/push via SendGrid/Twilio/FCM, dedup), Persistence/History Service (TimescaleDB hypertables, continuous aggregates, retention policies)
- **Section 2 — Design & Infrastructure Improvements:** Data persistence strategy (TimescaleDB + PostgreSQL + Redis), RabbitMQ DLQ topology with full diagram, retry policies, Resilience4j circuit breakers, broker security (TLS, per-service credentials, topic permissions), inter-service mTLS, JWT validation flow, observability (Micrometer/OTel/Zipkin tracing, Loki logging, Prometheus/Grafana metrics), **Gateway Redis Cache Layer** (device status cache, message deduplication via SET NX, per-device rate limiting, dynamic threshold config cache, Redis Docker Compose config)
- **Section 3 — Implementation Roadmap:** 4 phases over 16 weeks — Phase 1: Foundation & Resilience (Actuator, DLQs, publisher confirms, Resilience4j, Redis gateway cache with dedup/rate-limiting/thresholds, structured logging, tracing, Dockerfiles) → Phase 2: Core New Services (TimescaleDB, Persistence Service, Device Registry, device status caching, integration tests) → Phase 3: Security & Notifications (IAM, OAuth2 resource server, RabbitMQ TLS, Notification Service, Spring Cloud Gateway, mTLS) → Phase 4: Kubernetes & CI/CD (Helm charts, RabbitMQ Operator, observability stack, Vault, CI/CD pipeline, Istio, load testing)
- **Target architecture diagram** (ASCII) and **technology choices summary table**

### 3. Redis at Gateway Layer (added to plan after discussion)

User asked whether Redis should be added at the gateway layer. Analysis confirmed four high-value use cases: device status cache (TTL 60s + pub/sub invalidation), message deduplication (SET NX EX 300), per-device rate limiting (INCR/EXPIRE sliding window), dynamic threshold config. Added as Section 2.5 in the plan and integrated into Phase 1 (steps 1.5–1.8) and Phase 2 (step 2.4).

## Pending Task — NOT YET STARTED

### Frontend Dashboard Plan

The user requested a comprehensive frontend dashboard implementation plan to be **added to `production_plan.md`**. The request was detailed and covers:

1. **Technology Stack:** Frontend framework (React + Next.js or Vite), state management for real-time data (TanStack Query), visualization libraries (Recharts or D3.js) for time-series telemetry
2. **Core Functional Modules:**
   - Real-time Dashboard — live sensor readings via WebSockets or SSE connected to `processing-service`
   - Historical Analytics — querying/graphing historical trends from `persistence-service` (TimescaleDB)
   - Device Management UI — interface for `device-registry-service` (view metadata, status, lifecycle actions)
   - Alert Center — real-time anomaly alerts + searchable AlertEvent history
3. **Integration & Security:**
   - Authentication — login flow, JWT token management (storage, interceptors, refresh logic) with `IAM-service`
   - API Gateway — routing frontend requests through Spring Cloud Gateway
4. **Implementation Phases:** Prioritized rollout (e.g., Phase 1: Real-time Monitoring, Phase 2: History & Auth, Phase 3: Device Management) including Dockerization and CI/CD

**Important context for the next session:**
- The user is learning by building this project — the plan should be educational and hands-on
- The plan should be appended to the existing `production_plan.md` (currently 466 lines, ends with the technology choices summary table)
- It should align with the existing backend phases (the backend's Phase 2 builds the persistence-service and device-registry that the dashboard depends on; Phase 3 builds IAM and notification-service)
- The processing-service already has REST endpoints the dashboard can consume immediately (see REST API table above)
- A new SSE or WebSocket endpoint will need to be added to processing-service for real-time streaming to the browser
- The plan needs to specify where the frontend lives in the repo structure (likely a new `dashboard/` directory at root, outside the Maven multi-module)

## User Preferences

- Learning-oriented — the user is building this project to learn production patterns
- Prefers concrete, actionable plans with specific technology choices and code-level guidance
- Wants plans written to `production_plan.md` (not just chat responses)
- Engaged and asks good follow-up questions (e.g., the Redis gateway cache discussion)
