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

RabbitMQ uses TLS for inter-service communication. Generate the certificates before starting the stack:

```bash
cd certs
bash generate-certs.sh
cd ..
```

This creates the CA certificate, server certificate, and a PKCS12 truststore used by the Spring Boot services.

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

| Service                  | Port  | Description                          |
|--------------------------|-------|--------------------------------------|
| Sensor Simulator         | 8081  | Publishes synthetic sensor readings  |
| Gateway Service          | 8082  | Validates, dedupes, rate-limits data |
| Processing Service       | 8083  | Analytics and alert handling         |
| Device Registry Service  | 8084  | Device metadata and lifecycle        |
| History Service          | 8085  | Persists telemetry to TimescaleDB    |
| Notification Service     | 8086  | Alert notifications and preferences  |
| IAM Service              | 9000  | OAuth 2.1 / OIDC Authorization Server |
| RabbitMQ Management UI   | 15672 | Broker dashboard (admin / admin-secret) |
| Zipkin                   | 9411  | Distributed tracing UI              |
| Prometheus               | 9090  | Metrics scraping                    |
| Grafana                  | 3000  | Dashboards (admin / admin)          |
| Redis                    | 6379  | Dedup, rate-limiting, thresholds    |
| TimescaleDB (PostgreSQL) | 5432  | Time-series and relational storage  |

## Step 5 — Verify Services Are Running

Check that all containers are healthy:

```bash
docker compose ps
```

You can also verify individual service health endpoints:

```bash
curl http://localhost:8082/actuator/health   # Gateway
curl http://localhost:8083/actuator/health   # Processing
curl http://localhost:8084/actuator/health   # Device Registry
curl http://localhost:8085/actuator/health   # History
curl http://localhost:8086/actuator/health   # Notification
curl http://localhost:9000/actuator/health   # IAM
curl http://localhost:8081/actuator/health   # Sensor Simulator
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

> **Note:** When running outside Docker, services connect to RabbitMQ on `localhost:5672` (plain AMQP) instead of the TLS port used inside Docker Compose.
