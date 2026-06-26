# IoT Smart Home Monitor - Local Setup Guide

## Prerequisites

Make sure the following tools are installed on your machine:

- **Java 25** (JDK) — required to build the project
- **Maven 3.9+** — or use the included `./mvnw` wrapper
- **Docker & Docker Compose** — required to run infrastructure and services
- **OpenSSL** — required to generate TLS certificates

## Step 1 — Clone the Repository

```bash
git clone <repository-url>
cd iot
```

## Step 2 — Generate TLS Certificates

All inter-service communication uses TLS. RabbitMQ uses AMQPS (port 5671) and all REST services run HTTPS with mutual TLS (mTLS). Generate the certificates before starting the stack:

```bash
cd certs
bash generate-certs.sh
cd ..
```

This creates:
- **CA certificate** (`ca.pem`, `ca-key.pem`) — the SmartHome Dev CA
- **CA truststore** (`ca-truststore.p12`) — PKCS12 truststore containing only the CA cert
- **RabbitMQ server cert** (`server.pem`, `server-key.pem`)
- **Per-service PKCS12 keystores** (`gateway-service.p12`, `device-registry-service.p12`, etc.) — one per microservice, each containing the service certificate + private key signed by the CA, used for both server HTTPS and client mTLS

## Step 3 — Build the Project

Build all modules from the project root:

```bash
./mvnw clean package -DskipTests
```

Or if you have Maven installed globally:

```bash
mvn clean package -DskipTests
```

## Step 4 — Start the Full Stack with Docker Compose

```bash
docker compose up --build -d
```

This starts all infrastructure and application services:

| Service                  | Port  | Protocol | Description                          |
|--------------------------|-------|----------|--------------------------------------|
| API Gateway              | 8080  | HTTPS    | Single entry point — JWT validation, rate limiting |
| Sensor Simulator         | 8081  | HTTPS    | Publishes synthetic sensor readings  |
| Gateway Service          | 8082  | HTTPS    | Validates, dedupes, rate-limits data |
| Processing Service       | 8083  | HTTPS    | Analytics and alert handling         |
| Device Registry Service  | 8084  | HTTPS    | Device metadata and lifecycle        |
| History Service          | 8085  | HTTPS    | Persists telemetry to TimescaleDB    |
| Notification Service     | 8086  | HTTPS    | Alert notifications and preferences  |
| IAM Service              | 9000  | HTTPS    | OAuth 2.1 / OIDC Authorization Server |
| RabbitMQ Management UI   | 15672 | HTTP     | Broker dashboard (admin / admin-secret) |
| RabbitMQ AMQPS           | 5671  | TLS      | Encrypted message broker port        |
| Zipkin                   | 9411  | HTTP     | Distributed tracing UI              |
| Prometheus               | 9090  | HTTP     | Metrics scraping (HTTPS to targets) |
| Grafana                  | 3000  | HTTP     | Dashboards (admin / admin)          |
| Redis                    | 6379  | —        | Dedup, rate-limiting, thresholds    |
| TimescaleDB (PostgreSQL) | 5432  | —        | Time-series and relational storage  |

## Step 5 — Verify Services Are Running

Check that all containers are healthy:

```bash
docker compose ps
```

You can also verify individual service health endpoints (use `-k` to accept self-signed certificates):

```bash
curl -k https://localhost:8080/actuator/health   # API Gateway
curl -k https://localhost:8082/actuator/health   # Gateway
curl -k https://localhost:8083/actuator/health   # Processing
curl -k https://localhost:8084/actuator/health   # Device Registry
curl -k https://localhost:8085/actuator/health   # History
curl -k https://localhost:8086/actuator/health   # Notification
curl -k https://localhost:9000/actuator/health   # IAM
curl -k https://localhost:8081/actuator/health   # Sensor Simulator
```

## Useful Commands

**View logs for a specific service:**

```bash
docker compose logs -f gateway-service
```

**Stop all services:**

```bash
docker compose down
```

**Stop and remove all data volumes:**

```bash
docker compose down -v
```

**Rebuild a single service:**

```bash
docker compose up --build -d gateway-service
```

## Running a Single Service Locally (Outside Docker)

If you want to run a service directly on your machine for development:

1. Make sure the infrastructure services (RabbitMQ, Redis, TimescaleDB, Zipkin) are running via Docker Compose:

   ```bash
   docker compose up -d rabbitmq redis timescaledb zipkin iam-service
   ```

2. Run the desired service with the Maven wrapper:

   ```bash
   ./mvnw -pl gateway-service spring-boot:run
   ```

> **Note:** When running outside Docker, services use plain HTTP (SSL defaults to `false`) and connect to RabbitMQ on `localhost:5672` (plain AMQP). TLS/mTLS is only active inside Docker Compose where the `SSL_ENABLED`, `SSL_KEYSTORE`, and `JAVA_TOOL_OPTIONS` environment variables are set.

## Security Overview

### mTLS (Phase 3.7)

All 8 application services run HTTPS with mutual TLS (`client-auth: need`) when deployed via Docker Compose. Each service has its own PKCS12 keystore containing a certificate signed by the SmartHome Dev CA.

**Key environment variables (set by Docker Compose):**

| Variable | Purpose |
|---|---|
| `SSL_ENABLED` | Enables `server.ssl` on the embedded Tomcat/Netty |
| `SSL_KEYSTORE` | Path to the service's PKCS12 keystore (e.g., `file:/certs/gateway-service.p12`) |
| `SSL_CLIENT_AUTH` | Set to `need` — requires clients to present a certificate |
| `SSL_TRUSTSTORE` | Path to the CA truststore for verifying client certs |
| `JAVA_TOOL_OPTIONS` | Sets JVM-level truststore so JWKS/HTTPS calls trust the CA |

**Inter-service REST calls with mTLS:**
- `gateway-service` → `device-registry-service` (RestClient with SSL bundle)
- `sensor-simulator-service` → `device-registry-service` (RestClient with SSL bundle)
- `api-gateway` → all 7 backend services (Spring Cloud Gateway HttpClient with SSL bundle)

### RabbitMQ TLS (Phase 3C)

RabbitMQ listens on AMQPS port 5671 with per-service least-privilege credentials defined in `rabbitmq/definitions.json`.

### JWT Authentication (Phase 3A/3B)

All REST endpoints are secured with OAuth 2.1 JWTs issued by the IAM service. The API Gateway validates tokens at the edge; downstream services also validate independently via JWKS.
