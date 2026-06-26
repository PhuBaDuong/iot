# Phase 4: Kubernetes & CI/CD — Implementation Guide

> **Audience:** Developer new to DevOps, familiar with Docker Compose
> **Project:** IoT Smart Home Monitor (Spring Boot 4.0.1, Java 25, 8 microservices)
> **Goal:** Transition from `docker compose up` to a production-grade Kubernetes deployment

---

## Table of Contents

1. [Why Kubernetes?](#1-why-kubernetes)
2. [Containerization Refinement](#2-containerization-refinement)
3. [Orchestration with Helm](#3-orchestration-with-helm)
4. [Infrastructure as Code — Operators](#4-infrastructure-as-code--operators)
5. [Security & Secrets Management](#5-security--secrets-management)
6. [CI/CD Pipeline Design](#6-cicd-pipeline-design)
7. [Observability in Kubernetes](#7-observability-in-kubernetes)
8. [Migration Roadmap](#8-migration-roadmap)

---

## 1. Why Kubernetes?

### The Problem Docker Compose Solves — and Where It Stops

Docker Compose is a **single-host orchestrator**. It starts containers in order, wires
them together on a bridge network, and mounts local volumes. This is excellent for local
development, but it cannot:

| Capability | Docker Compose | Kubernetes |
|---|---|---|
| Multi-node scaling | ❌ Single host only | ✅ Distributes pods across nodes |
| Self-healing | ❌ Containers stay dead | ✅ Restarts failed pods automatically |
| Rolling updates | ❌ Must `down` + `up` | ✅ Zero-downtime rollouts |
| Auto-scaling | ❌ Manual `replicas` | ✅ HPA scales on CPU/memory/custom metrics |
| Service discovery | DNS by container name | DNS + Endpoints API + Ingress |
| Secret rotation | ❌ Env vars are static | ✅ Vault CSI driver mounts rotate |
| Certificate management | ❌ Manual `generate-certs.sh` | ✅ cert-manager auto-issues & renews |
| Config reload | Restart required | ✅ ConfigMap hot-reload in Spring Cloud |

### Mental Model: Docker Compose → Kubernetes Mapping

```
Docker Compose concept     →  Kubernetes concept
───────────────────────────────────────────────────
docker-compose.yml         →  Helm chart (templates/ dir)
service:                   →  Deployment + Service
ports:                     →  Service (ClusterIP/NodePort/LoadBalancer)
environment:               →  ConfigMap + Secret
volumes: (data)            →  PersistentVolumeClaim (PVC)
volumes: (config)          →  ConfigMap mounted as file
depends_on:                →  Init containers + readiness probes
healthcheck:               →  livenessProbe + readinessProbe + startupProbe
docker compose up --build  →  helm install / helm upgrade
```

---

## 2. Containerization Refinement

### 2.1 What We Have Today

Our current Dockerfiles use a solid multi-stage build:

```dockerfile
# Build stage — full JDK + Maven
FROM maven:3.9-eclipse-temurin-25 AS build
# ... compile ...

# Runtime stage — JRE only
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y curl && useradd -r -u 1001 spring
COPY --from=build .../app.jar app.jar
USER spring
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**What's good:** multi-stage (small image), non-root user, JRE-only runtime.
**What to improve:** image size, security surface, health probes, resource governance.

### 2.2 Optimization 1 — Distroless or Alpine Base Images

**Why:** The `eclipse-temurin:25-jre` image is based on Ubuntu (~300 MB). It includes
a shell, package manager, and many OS utilities that our Java app never uses. Each
unnecessary binary is an attack surface.

**Option A — Eclipse Temurin Alpine (~150 MB):**

```dockerfile
FROM eclipse-temurin:25-jre-alpine
# Alpine uses musl libc instead of glibc. Test thoroughly.
RUN addgroup -S spring && adduser -S spring -G spring
```

**Option B — Google Distroless (~200 MB but zero shell):**

```dockerfile
FROM gcr.io/distroless/java21-debian12
# No shell, no package manager, no curl — just a JVM
# NOTE: As of 2026, distroless may not yet have Java 25.
# Use when available or stick with Alpine.
```

**Option C — Custom JRE with jlink (~100 MB):**

Spring Boot 4 supports creating a minimal custom JRE that includes only the Java
modules your app actually uses:

```dockerfile
FROM eclipse-temurin:25 AS jre-build
RUN jlink \
    --add-modules java.base,java.logging,java.sql,java.naming,java.management,\
java.instrument,java.security.jgss,java.net.http,jdk.unsupported,java.desktop \
    --strip-debug --no-man-pages --no-header-files \
    --compress zip-6 --output /custom-jre

FROM debian:bookworm-slim
COPY --from=jre-build /custom-jre /opt/java
ENV PATH="/opt/java/bin:$PATH"
```

**Recommendation for our project:** Start with **Eclipse Temurin Alpine** — it's the
smallest change with the biggest impact. Move to distroless when Java 25 images are
available.

### 2.3 Optimization 2 — Spring Boot Layered JARs

**Why:** Every time you change a single line of code, Docker rebuilds the entire
JAR layer (~80 MB). Spring Boot's layered JAR splits the fat JAR into 4 layers that
change at different frequencies:

```
Layer 1: dependencies/          (rarely changes)    ~70 MB
Layer 2: spring-boot-loader/    (almost never)      ~300 KB
Layer 3: snapshot-dependencies/ (sometimes)         varies
Layer 4: application/           (every build)       ~2 MB
```

**How:** Add an extraction step to the Dockerfile:

```dockerfile
FROM eclipse-temurin:25-jre-alpine AS layers
WORKDIR /app
COPY --from=build /workspace/gateway-service/target/gateway-service-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

### 2.4 Optimization 3 — Kubernetes Health Probes

**Why:** In Docker Compose, we use a single `healthcheck` with `curl`. Kubernetes has
**three** probe types, each serving a different purpose:

| Probe | Question It Answers | What Happens on Failure |
|---|---|---|
| `startupProbe` | "Has the app finished booting?" | Keeps waiting (no traffic, no restart) |
| `readinessProbe` | "Can this pod handle requests?" | Removes from Service (no traffic) |
| `livenessProbe` | "Is this pod stuck/deadlocked?" | Kills and restarts the pod |

**Current Docker Compose healthcheck:**
```yaml
healthcheck:
  test: ["CMD", "curl", "-fk", "https://localhost:8082/actuator/health"]
  start_period: 60s
```

**Kubernetes equivalent (in Helm template):**
```yaml
startupProbe:
  httpGet:
    path: /actuator/health
    port: 8082
    scheme: HTTPS
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 30        # 10s + (5s × 30) = up to 160s to start
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8082
    scheme: HTTPS
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8082
    scheme: HTTPS
  periodSeconds: 15
  failureThreshold: 3
```

**Spring Boot integration:** Add to `application.yml`:
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true       # Exposes /actuator/health/liveness and /readiness
      group:
        readiness:
          include: db, rabbit, redis   # Only "ready" when datastores are up
        liveness:
          include: livenessState       # Only checks if the JVM is alive
```

**Why three probes?** A Spring Boot app connecting to TimescaleDB, RabbitMQ, and Redis
can take 30–60 seconds to start. Without a `startupProbe`, Kubernetes would kill it
(via `livenessProbe`) before it finishes booting — causing a restart loop.

### 2.5 Optimization 4 — Resource Requests and Limits

**Why:** Without resource governance, a single leaking service can consume all memory
on a node and crash every other pod (the "noisy neighbor" problem).

```yaml
resources:
  requests:                    # Guaranteed minimum — scheduler uses this
    cpu: 250m                  # 0.25 CPU cores
    memory: 384Mi              # 384 MB RAM
  limits:                      # Hard ceiling — OOM-killed if exceeded
    cpu: "1"                   # 1 full core
    memory: 768Mi              # 768 MB RAM
```

**Sizing guide for our services:**

| Service | CPU Request | Memory Request | CPU Limit | Memory Limit | Notes |
|---|---|---|---|---|---|
| api-gateway | 250m | 256Mi | 1 | 512Mi | Reactive/Netty, low memory |
| gateway-service | 250m | 384Mi | 1 | 768Mi | Redis + RabbitMQ + REST |
| processing-service | 250m | 384Mi | 1 | 768Mi | Analytics, concurrent consumers |
| device-registry-service | 200m | 384Mi | 500m | 768Mi | JPA + Redis + RabbitMQ |
| history-service | 200m | 384Mi | 500m | 768Mi | JPA writes |
| notification-service | 200m | 256Mi | 500m | 512Mi | Lightweight consumer |
| sensor-simulator-service | 100m | 256Mi | 500m | 512Mi | Periodic publisher |
| iam-service | 200m | 384Mi | 500m | 768Mi | Auth server + JPA |

**Tip:** Start with generous limits and tune down based on Prometheus metrics
(`jvm_memory_used_bytes`, `process_cpu_usage`). The HPA (Horizontal Pod Autoscaler)
uses these requests to calculate scaling.

---

## 3. Orchestration with Helm

### 3.1 What Is Helm and Why Do We Need It?

**The problem:** Deploying 8 services to Kubernetes means writing 8 Deployments, 8
Services, 8 ConfigMaps, 8 Secrets, and potentially Ingress rules, HPAs, PDBs, and
NetworkPolicies. That's easily **50+ YAML files**, many of which are nearly identical.

**Helm** is a **package manager for Kubernetes** — like apt for Debian or brew for macOS.
It provides:

1. **Templating:** Write one template, render it 8 times with different values.
2. **Packaging:** Bundle all K8s resources into a single deployable unit (a "chart").
3. **Versioning:** Roll back to any previous release with `helm rollback`.
4. **Dependency management:** Declare that your chart depends on Redis, RabbitMQ, etc.

### 3.2 Chart Structure for Our Project

```
helm/
├── smarthome/                          # Umbrella chart
│   ├── Chart.yaml                      # Chart metadata + dependencies
│   ├── values.yaml                     # Default values for ALL services
│   ├── values-dev.yaml                 # Overrides for dev environment
│   ├── values-staging.yaml             # Overrides for staging
│   ├── values-prod.yaml               # Overrides for production
│   └── templates/
│       ├── _helpers.tpl                # Shared template functions
│       └── services/
│           ├── gateway-service.yaml
│           ├── processing-service.yaml
│           ├── device-registry.yaml
│           ├── history-service.yaml
│           ├── notification-service.yaml
│           ├── simulator-service.yaml
│           ├── iam-service.yaml
│           └── api-gateway.yaml
│
└── charts/                             # Reusable sub-chart
    └── spring-service/                 # Generic chart for any Spring Boot service
        ├── Chart.yaml
        ├── values.yaml
        └── templates/
            ├── deployment.yaml
            ├── service.yaml
            ├── configmap.yaml
            ├── secret.yaml
            ├── hpa.yaml
            └── ingress.yaml
```

**Key idea:** We create ONE reusable sub-chart called `spring-service` that knows how
to deploy any Spring Boot service. Each actual service just provides different `values`.

### 3.3 The Reusable Spring Service Template

Here's what `charts/spring-service/templates/deployment.yaml` would look like
(simplified for learning):

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "spring-service.fullname" . }}
  labels:
    {{- include "spring-service.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "spring-service.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "spring-service.selectorLabels" . | nindent 8 }}
      annotations:
        # Force rollout when ConfigMap changes
        checksum/config: {{ include (print .Template.BasePath "/configmap.yaml") . |
          sha256sum }}
    spec:
      serviceAccountName: {{ .Values.serviceAccountName | default "default" }}
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.service.port }}
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "spring-service.fullname" . }}
            - secretRef:
                name: {{ include "spring-service.fullname" . }}
          {{- if .Values.probes.startup.enabled }}
          startupProbe:
            httpGet:
              path: {{ .Values.probes.startup.path }}
              port: {{ .Values.service.port }}
              scheme: {{ .Values.probes.scheme | default "HTTPS" }}
            initialDelaySeconds: {{ .Values.probes.startup.initialDelaySeconds }}
            periodSeconds: {{ .Values.probes.startup.periodSeconds }}
            failureThreshold: {{ .Values.probes.startup.failureThreshold }}
          {{- end }}
          readinessProbe:
            httpGet:
              path: {{ .Values.probes.readiness.path }}
              port: {{ .Values.service.port }}
              scheme: {{ .Values.probes.scheme | default "HTTPS" }}
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: {{ .Values.probes.liveness.path }}
              port: {{ .Values.service.port }}
              scheme: {{ .Values.probes.scheme | default "HTTPS" }}
            periodSeconds: 15
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
          volumeMounts:
            - name: certs
              mountPath: /certs
              readOnly: true
      volumes:
        - name: certs
          secret:
            secretName: {{ .Values.tls.secretName | default "smarthome-certs" }}
```

### 3.4 values.yaml — Configuring Each Service

The **umbrella chart's** `values.yaml` defines all 8 services:

```yaml
# helm/smarthome/values.yaml (excerpt for gateway-service)

gateway-service:
  replicaCount: 2
  image:
    repository: ghcr.io/your-org/smarthome-gateway-service
    tag: "1.0.0"
    pullPolicy: IfNotPresent
  service:
    port: 8082
    type: ClusterIP
  resources:
    requests:
      cpu: 250m
      memory: 384Mi
    limits:
      cpu: "1"
      memory: 768Mi
  probes:
    scheme: HTTPS
    startup:
      enabled: true
      path: /actuator/health
      initialDelaySeconds: 10
      periodSeconds: 5
      failureThreshold: 30
    readiness:
      path: /actuator/health/readiness
    liveness:
      path: /actuator/health/liveness
  config:
    # These become the ConfigMap (non-sensitive config)
    RABBITMQ_HOST: smarthome-rabbitmq
    RABBITMQ_PORT: "5671"
    REDIS_HOST: smarthome-redis
    REDIS_PORT: "6379"
    DEVICE_REGISTRY_URL: "https://device-registry-service:8084"
    IAM_ISSUER: "https://iam-service:9000"
    ZIPKIN_ENDPOINT: "http://smarthome-zipkin:9411/api/v2/spans"
    SSL_ENABLED: "true"
    SSL_CLIENT_AUTH: "need"
  secrets:
    # These become the Secret (sensitive — managed by Vault in production)
    RABBITMQ_USERNAME: gateway-svc
    RABBITMQ_PASSWORD: gw-secret-2024      # Overridden by Vault in prod
    SSL_KEYSTORE_PASSWORD: changeit
    SSL_TRUSTSTORE_PASSWORD: changeit
  tls:
    secretName: smarthome-gateway-tls
  hpa:
    enabled: true
    minReplicas: 2
    maxReplicas: 10
    targetCPUUtilization: 70
```

### 3.5 ConfigMap vs. Secret — What Goes Where?

```
                    ┌─────────────────────────────────┐
                    │        Environment Variables     │
                    └──────────┬──────────────────────┘
                               │
              ┌────────────────┼────────────────┐
              ▼                                 ▼
     ┌─────────────────┐              ┌─────────────────┐
     │    ConfigMap     │              │     Secret      │
     │  (plain text)   │              │  (base64/Vault) │
     ├─────────────────┤              ├─────────────────┤
     │ RABBITMQ_HOST   │              │ RABBITMQ_PASSWORD│
     │ REDIS_HOST      │              │ DB_PASSWORD      │
     │ IAM_ISSUER      │              │ SSL_KEYSTORE_PWD │
     │ ZIPKIN_ENDPOINT │              │ PKCS12 keystores │
     │ SSL_ENABLED     │              │ ca-truststore.p12│
     │ SSL_CLIENT_AUTH │              │                  │
     └─────────────────┘              └─────────────────┘
```

**Rule of thumb:** If you'd be uncomfortable seeing the value in a Git repo, it's a
Secret. Everything else is a ConfigMap.

### 3.6 How application.yml Is Managed

**Current approach** (Docker Compose): `application.yml` is baked into the JAR. Env vars
override specific properties at runtime.

**Kubernetes approach:** Same pattern — the JAR still contains the default `application.yml`,
and the ConfigMap + Secret provide environment variable overrides. This is why our existing
`${VAR:default}` pattern in `application.yml` works perfectly in Kubernetes with zero changes.

For more complex config changes, you can mount a full `application.yml` as a ConfigMap:

```yaml
# In the Deployment spec:
volumeMounts:
  - name: app-config
    mountPath: /app/config/application.yml
    subPath: application.yml
volumes:
  - name: app-config
    configMap:
      name: gateway-service-config

# Spring Boot auto-loads /app/config/application.yml when it exists
```

### 3.7 Deploying with Helm

```bash
# First time install
helm install smarthome ./helm/smarthome \
  -f helm/smarthome/values-dev.yaml \
  --namespace smarthome --create-namespace

# Upgrade (after code/config changes)
helm upgrade smarthome ./helm/smarthome \
  -f helm/smarthome/values-staging.yaml \
  --namespace smarthome

# Rollback to previous version
helm rollback smarthome 1 --namespace smarthome

# See release history
helm history smarthome --namespace smarthome
```

---

## 4. Infrastructure as Code — Operators

### 4.1 Why Not Just Deploy RabbitMQ as a Deployment?

You *could* create a Kubernetes Deployment for RabbitMQ, like we do in Docker Compose.
But stateful services have special requirements that Deployments can't handle:

| Concern | Deployment (Stateless) | Operator (Stateful-aware) |
|---|---|---|
| Stable network identity | ❌ Pods get random names | ✅ `rabbit-0`, `rabbit-1`, `rabbit-2` |
| Persistent storage | Manual PVC setup | ✅ Auto-provisions PVCs per replica |
| Cluster formation | Manual config | ✅ Nodes auto-discover and join |
| Rolling upgrades | Kills all pods at once | ✅ Upgrades one node at a time |
| Backup/restore | Manual scripting | ✅ Built-in snapshot support |
| TLS renewal | Manual cert rotation | ✅ Integrates with cert-manager |
| Monitoring | Manual ServiceMonitor | ✅ Auto-creates Prometheus targets |

**What is an Operator?** An Operator is a Kubernetes controller that understands how
to manage a specific application. You describe what you want (e.g., "3-node RabbitMQ
cluster with TLS"), and the Operator figures out the how.

### 4.2 RabbitMQ — Cluster Operator

**Install the Operator:**

```bash
# Install the RabbitMQ Cluster Operator
kubectl apply -f \
  https://github.com/rabbitmq/cluster-operator/releases/latest/download/cluster-operator.yml
```

**Define the cluster (Custom Resource):**

```yaml
# helm/smarthome/templates/rabbitmq-cluster.yaml
apiVersion: rabbitmq.com/v1beta1
kind: RabbitmqCluster
metadata:
  name: smarthome-rabbitmq
  namespace: smarthome
spec:
  replicas: 3                               # 3-node cluster for HA
  image: rabbitmq:3.13-management
  resources:
    requests:
      cpu: 500m
      memory: 512Mi
    limits:
      cpu: "2"
      memory: 1Gi
  persistence:
    storageClassName: standard               # Use your cluster's storage class
    storage: 10Gi                            # Per-node persistent volume
  rabbitmq:
    additionalConfig: |
      # Import our definitions (users, exchanges, queues, bindings)
      load_definitions = /etc/rabbitmq/definitions.json
      # TLS
      listeners.ssl.default = 5671
      ssl_options.cacertfile = /etc/rabbitmq/certs/ca.pem
      ssl_options.certfile = /etc/rabbitmq/certs/server.pem
      ssl_options.keyfile = /etc/rabbitmq/certs/server-key.pem
      ssl_options.versions.1 = tlsv1.3
      ssl_options.versions.2 = tlsv1.2
  tls:
    secretName: rabbitmq-tls                 # K8s Secret with TLS certs
```

**What the Operator does for us:**
1. Creates a StatefulSet with 3 pods (`smarthome-rabbitmq-server-0`, `-1`, `-2`)
2. Each pod gets its own PersistentVolumeClaim (data survives restarts)
3. Nodes auto-discover each other via DNS and form a cluster
4. Quorum queues replicate messages across nodes (no data loss if one node dies)
5. Rolling upgrades: takes one node offline, upgrades it, rejoins, then the next

**Migrating our definitions.json:** The Operator can load our existing definitions file
via a ConfigMap mount. Our current `rabbitmq/definitions.json` (users, exchanges,
queues, bindings) works as-is.

### 4.3 TimescaleDB

TimescaleDB runs on PostgreSQL, so we use a PostgreSQL Operator:

**Option A — CloudNativePG (recommended):**

```yaml
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: smarthome-timescaledb
spec:
  instances: 3                             # 1 primary + 2 read replicas
  imageName: timescale/timescaledb:2.17.2-pg16
  storage:
    size: 20Gi
    storageClass: standard
  bootstrap:
    initdb:
      database: telemetry
      owner: smarthome
      postInitSQL:
        - CREATE DATABASE devices OWNER smarthome;
        - CREATE DATABASE notifications OWNER smarthome;
        - CREATE DATABASE smarthome_iam OWNER smarthome;
  postgresql:
    parameters:
      shared_preload_libraries: timescaledb
      max_connections: "200"
  backup:
    barmanObjectStore:                     # Automated backups to S3
      destinationPath: s3://backups/tsdb/
      s3Credentials:
        accessKeyId:
          name: backup-creds
          key: ACCESS_KEY_ID
        secretAccessKey:
          name: backup-creds
          key: SECRET_ACCESS_KEY
```

**Why an Operator for the database?**
- **Automated failover:** If the primary dies, a replica is promoted in seconds.
- **Point-in-time recovery:** Continuous WAL archiving to S3 means you can restore
  to any point in the last N days.
- **Connection pooling:** PgBouncer sidecar handles connection management.
- **Our init scripts work as-is:** The `postInitSQL` replaces our
  `db/timescaledb/init/` Docker entrypoint scripts.

### 4.4 Redis

Redis is simpler — a standalone instance or Sentinel cluster:

```bash
# Using the Bitnami Helm chart
helm install smarthome-redis oci://registry-1.docker.io/bitnamicharts/redis \
  --set architecture=replication \
  --set replica.replicaCount=2 \
  --set auth.password=your-redis-password \
  --namespace smarthome
```

---

## 5. Security & Secrets Management

### 5.1 The Current Problem

In our Docker Compose setup, secrets are managed in **three problematic ways:**

```yaml
# Problem 1: Passwords in plain text in docker-compose.yml
RABBITMQ_PASSWORD: gw-secret-2024
DB_PASSWORD: smarthome123

# Problem 2: Certificate passwords hardcoded
SSL_KEYSTORE_PASSWORD: changeit

# Problem 3: Certificates generated manually and mounted as files
volumes:
  - ./certs:/certs:ro
```

Anyone with access to the Git repo or the docker-compose.yml file can see every
credential. This is unacceptable in production.

### 5.2 Kubernetes Native Secrets (Good Starting Point)

Kubernetes Secrets are base64-encoded (NOT encrypted by default) but provide:
- RBAC: Only authorized pods/service accounts can read them
- Audit logging: You can see who accessed which secret
- Namespace isolation: Secrets in namespace A are invisible to namespace B

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: gateway-service-secrets
  namespace: smarthome
type: Opaque
stringData:                               # K8s auto-encodes to base64
  RABBITMQ_USERNAME: gateway-svc
  RABBITMQ_PASSWORD: gw-secret-2024
  DB_PASSWORD: smarthome123
  SSL_KEYSTORE_PASSWORD: changeit
---
# TLS certificate secret
apiVersion: v1
kind: Secret
metadata:
  name: smarthome-gateway-tls
type: kubernetes.io/tls
data:
  keystore.p12: <base64-encoded PKCS12>   # From generate-certs.sh output
  truststore.p12: <base64-encoded CA truststore>
```

**Limitation:** The Secret YAML still contains credentials. If stored in Git, it's
just as bad as docker-compose.yml. Solutions: **Sealed Secrets** or **Vault**.

### 5.3 HashiCorp Vault (Production-Grade)

**What Vault solves:**
1. **Centralized secret storage** — secrets never live in Git or K8s etcd
2. **Dynamic secrets** — Vault generates short-lived database credentials on demand
3. **Automatic rotation** — credentials expire and refresh without restarts
4. **Audit trail** — every secret access is logged

**Architecture:**

```
┌──────────────────────────────────────────────────────────────┐
│                    Kubernetes Cluster                         │
│                                                              │
│  ┌─────────┐      ┌──────────────────┐     ┌─────────────┐  │
│  │  Vault   │◄────│ Vault CSI Driver │────►│ gateway-svc │  │
│  │  Server  │     │ (DaemonSet)      │     │   Pod       │  │
│  │          │     └──────────────────┘     │             │  │
│  │ Secrets: │                              │ /vault/     │  │
│  │ db/creds │     ┌──────────────────┐     │  secrets/   │  │
│  │ rabbit/* │◄────│ Vault Agent      │────►│  db-pass    │  │
│  │ pki/cert │     │ Injector (Mutating│    │  rabbit-pw  │  │
│  │          │     │  Webhook)        │     │  tls.p12    │  │
│  └─────────┘     └──────────────────┘     └─────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

**Two integration methods:**

**Method A — Vault Agent Injector (annotations-based):**

```yaml
# Add annotations to the Deployment pod template
metadata:
  annotations:
    vault.hashicorp.com/agent-inject: "true"
    vault.hashicorp.com/role: "gateway-service"
    vault.hashicorp.com/agent-inject-secret-db: "database/creds/gateway"
    vault.hashicorp.com/agent-inject-template-db: |
      {{- with secret "database/creds/gateway" -}}
      DB_USERNAME={{ .Data.username }}
      DB_PASSWORD={{ .Data.password }}
      {{- end }}
```

**Method B — Vault CSI Driver (file-mount-based):**

```yaml
volumes:
  - name: vault-secrets
    csi:
      driver: secrets-store.csi.k8s.io
      readOnly: true
      volumeAttributes:
        secretProviderClass: gateway-service-secrets
```

### 5.4 cert-manager — Replacing generate-certs.sh

**Why:** Our current `generate-certs.sh` script generates certificates once, manually.
In production, certificates must be rotated regularly (90 days is common). cert-manager
automates this entirely.

```bash
# Install cert-manager
helm install cert-manager jetstack/cert-manager \
  --namespace cert-manager --create-namespace \
  --set crds.enabled=true
```

```yaml
# Define our internal CA as a ClusterIssuer
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: smarthome-ca-issuer
spec:
  ca:
    secretName: smarthome-ca-keypair       # Contains ca.pem + ca-key.pem

---
# Request a certificate for gateway-service
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: gateway-service-cert
  namespace: smarthome
spec:
  secretName: gateway-service-tls          # K8s Secret with the cert
  issuerRef:
    name: smarthome-ca-issuer
    kind: ClusterIssuer
  commonName: gateway-service
  dnsNames:
    - gateway-service
    - gateway-service.smarthome.svc.cluster.local
  duration: 2160h                          # 90 days
  renewBefore: 360h                        # Renew 15 days before expiry
  privateKey:
    algorithm: RSA
    size: 4096
  keystores:
    pkcs12:
      create: true
      passwordSecretRef:
        name: keystore-password
        key: password
```

**What cert-manager does for us:**
1. Generates the certificate and private key
2. Stores them in a Kubernetes Secret (`gateway-service-tls`)
3. Monitors expiry and auto-renews 15 days before expiration
4. Creates PKCS12 keystores (matching our Spring Boot `server.ssl.key-store` format)
5. Spring Boot can hot-reload the cert via SSL bundle file watching

**This completely replaces** our `certs/generate-certs.sh` script in production.

### 5.5 Migration Path — Current to Production

```
Phase 4a (Start here):
  docker-compose.yml passwords  →  Kubernetes Secrets (YAML, not in Git)
  ./certs volume mounts         →  TLS Secrets mounted into pods
  JAVA_TOOL_OPTIONS truststore  →  JVM truststore from Secret volume mount

Phase 4b (Vault):
  Kubernetes Secrets            →  Vault dynamic secrets
  Static RabbitMQ passwords     →  Vault-generated rotating credentials
  Static DB passwords           →  Vault database engine (auto-rotating)
  Manual PKCS12 generation      →  cert-manager + Vault PKI engine

Phase 4c (Full automation):
  cert-manager watches expiry and auto-renews all service certificates
  Vault Agent sidecar injects fresh credentials into pods at startup
  No human ever touches a password or certificate
```

---

## 6. CI/CD Pipeline Design

### 6.1 Pipeline Overview

```
┌────────┐   ┌──────┐   ┌──────┐   ┌──────────┐   ┌──────────┐   ┌────────┐
│  Push  │──►│ Lint │──►│ Test │──►│  Build   │──►│  Scan    │──►│ Deploy │
│ to Git │   │      │   │      │   │ + Push   │   │          │   │        │
└────────┘   │Check │   │Unit  │   │ Docker   │   │Container │   │Helm    │
             │style │   │+ Int │   │ Images   │   │Security  │   │Upgrade │
             │& fmt │   │tests │   │ to GHCR  │   │ (Trivy)  │   │to K8s  │
             └──────┘   └──────┘   └──────────┘   └──────────┘   └────────┘
```

### 6.2 GitHub Actions Workflow — Explained Stage by Stage

```yaml
# .github/workflows/ci-cd.yml
name: CI/CD Pipeline

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

# Cancel redundant runs (e.g., rapid pushes)
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

env:
  JAVA_VERSION: '25'
  REGISTRY: ghcr.io
  IMAGE_PREFIX: ${{ github.repository_owner }}/smarthome

jobs:
  # =========================================================================
  # Stage 1: LINT
  # =========================================================================
  # WHY: Catch style issues before wasting CI time on builds/tests.
  # Runs in ~30 seconds. Fails fast if code doesn't meet standards.
  # =========================================================================
  lint:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK ${{ env.JAVA_VERSION }}
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven                         # Cache ~/.m2 across runs

      - name: Check code formatting
        run: ./mvnw -B checkstyle:check        # Or spotless:check

  # =========================================================================
  # Stage 2: UNIT TESTS
  # =========================================================================
  # WHY: Fast feedback (no Docker needed). Tests business logic in isolation.
  # Our 26 security tests + all unit tests run here.
  # =========================================================================
  unit-test:
    runs-on: ubuntu-latest
    needs: lint                                # Only run if lint passes
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Run unit tests
        run: ./mvnw -B verify -DexcludeGroups=integration
        # -DexcludeGroups=integration skips tests tagged @Tag("integration")
        # Our security tests (@WebMvcTest) run here — they don't need Docker

      - name: Upload test reports
        if: always()                           # Upload even on failure
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/target/surefire-reports/'

  # =========================================================================
  # Stage 3: INTEGRATION TESTS (Testcontainers)
  # =========================================================================
  # WHY: Verifies the app works with REAL databases, REAL RabbitMQ, etc.
  # Testcontainers spins up Docker containers for each dependency.
  # Slower (~5 min) but catches issues that mocks miss.
  # =========================================================================
  integration-test:
    runs-on: ubuntu-latest
    needs: unit-test
    services:
      # GitHub Actions provides a Docker daemon for Testcontainers
      docker:
        image: docker:dind
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: ${{ env.JAVA_VERSION }}
          distribution: temurin
          cache: maven

      - name: Run integration tests
        run: ./mvnw -B verify -DincludeGroups=integration
        env:
          TESTCONTAINERS_RYUK_DISABLED: false

  # =========================================================================
  # Stage 4: BUILD & PUSH DOCKER IMAGES
  # =========================================================================
  # WHY: Creates the container images and stores them in a registry.
  # Uses Docker layer caching for speed. Tags with Git SHA for traceability.
  # =========================================================================
  build-images:
    runs-on: ubuntu-latest
    needs: [unit-test, integration-test]
    permissions:
      contents: read
      packages: write                          # Push to GHCR
    strategy:
      matrix:
        service:
          - gateway-service
          - processing-service
          - device-registry-service
          - history-service
          - notification-service
          - sensor-simulator-service
          - iam-service
          - api-gateway
    steps:
      - uses: actions/checkout@v4

      - name: Log in to GitHub Container Registry
        uses: docker/login-action@v3
        with:
          registry: ${{ env.REGISTRY }}
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and push ${{ matrix.service }}
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.service }}/Dockerfile
          push: true
          tags: |
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}-${{ matrix.service }}:${{ github.sha }}
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}-${{ matrix.service }}:latest
          cache-from: type=gha
          cache-to: type=gha,mode=max

  # =========================================================================
  # Stage 5: CONTAINER SECURITY SCAN
  # =========================================================================
  # WHY: Checks Docker images for known CVEs (vulnerabilities) in OS packages
  # and Java dependencies. Blocks deployment if critical issues are found.
  # =========================================================================
  scan:
    runs-on: ubuntu-latest
    needs: build-images
    strategy:
      matrix:
        service:
          - gateway-service
          - processing-service
          - device-registry-service
          - history-service
          - notification-service
          - sensor-simulator-service
          - iam-service
          - api-gateway
    steps:
      - name: Run Trivy vulnerability scanner
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: >-
            ${{ env.REGISTRY }}/${{ env.IMAGE_PREFIX }}-${{ matrix.service }}:${{ github.sha }}
          format: table
          exit-code: 1                         # Fail pipeline on HIGH/CRITICAL
          severity: HIGH,CRITICAL
          ignore-unfixed: true                 # Don't fail on unpatched issues

  # =========================================================================
  # Stage 6: DEPLOY TO KUBERNETES
  # =========================================================================
  # WHY: Automatically deploys to staging after all checks pass.
  # Production requires manual approval (environment protection rule).
  # =========================================================================
  deploy-staging:
    runs-on: ubuntu-latest
    needs: scan
    if: github.ref == 'refs/heads/main'        # Only deploy from main branch
    environment: staging                        # GitHub Environment for approvals
    steps:
      - uses: actions/checkout@v4

      - name: Set up Helm
        uses: azure/setup-helm@v3

      - name: Configure kubeconfig
        uses: azure/k8s-set-context@v4
        with:
          kubeconfig: ${{ secrets.KUBE_CONFIG_STAGING }}

      - name: Deploy to staging
        run: |
          helm upgrade --install smarthome ./helm/smarthome \
            -f helm/smarthome/values-staging.yaml \
            --set global.image.tag=${{ github.sha }} \
            --namespace smarthome \
            --create-namespace \
            --wait --timeout 10m

      - name: Run smoke tests
        run: |
          # Wait for all pods to be ready
          kubectl wait --for=condition=ready pod \
            -l app.kubernetes.io/part-of=smarthome \
            --timeout=300s -n smarthome

          # Hit the health endpoint through the Ingress
          curl -fk https://staging.smarthome.example.com/actuator/health

  deploy-production:
    runs-on: ubuntu-latest
    needs: deploy-staging
    environment: production                    # Requires manual approval in GitHub
    steps:
      - uses: actions/checkout@v4

      - name: Set up Helm
        uses: azure/setup-helm@v3

      - name: Configure kubeconfig
        uses: azure/k8s-set-context@v4
        with:
          kubeconfig: ${{ secrets.KUBE_CONFIG_PRODUCTION }}

      - name: Deploy to production
        run: |
          helm upgrade --install smarthome ./helm/smarthome \
            -f helm/smarthome/values-prod.yaml \
            --set global.image.tag=${{ github.sha }} \
            --namespace smarthome \
            --wait --timeout 15m
```

### 6.3 Understanding the Pipeline Flow

```
Developer pushes code to `main`
     │
     ▼
┌─ Lint ──────────────────────────────────────────────────────┐
│  Checkstyle/Spotless verifies code style (~30s)             │
│  FAIL → pipeline stops, developer fixes formatting          │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Unit Tests ────────────────────────────────────────────────┐
│  26 security tests + all @Test methods (~2 min)             │
│  No Docker needed — mocks and @WebMvcTest only              │
│  FAIL → developer gets test report artifact                 │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Integration Tests ─────────────────────────────────────────┐
│  Testcontainers starts real RabbitMQ, PostgreSQL, Redis     │
│  Verifies actual DB migrations, AMQP bindings, etc. (~5m)  │
│  FAIL → something wrong with infra integration              │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Build Images ──────────────────────────────────────────────┐
│  8 Docker images built in PARALLEL (matrix strategy)        │
│  Pushed to ghcr.io with SHA tag + latest                    │
│  Layer caching via GitHub Actions cache (~3 min)            │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Security Scan ─────────────────────────────────────────────┐
│  Trivy scans all 8 images for CVEs in PARALLEL              │
│  Blocks deployment if HIGH/CRITICAL vulnerabilities found   │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Deploy Staging ────────────────────────────────────────────┐
│  helm upgrade to staging cluster                            │
│  Runs smoke tests against staging                           │
│  Automatic — no approval needed                             │
└─────────────────────────────────────┬───────────────────────┘
                                      │ PASS
                                      ▼
┌─ Deploy Production ─────────────────────────────────────────┐
│  REQUIRES MANUAL APPROVAL in GitHub UI                      │
│  helm upgrade to production cluster                         │
│  Zero-downtime rolling update                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 7. Observability in Kubernetes

### 7.1 Why kube-prometheus-stack?

In Docker Compose, we manually configured Prometheus and Grafana. In Kubernetes,
`kube-prometheus-stack` is the standard — it deploys the entire observability stack
with a single Helm install:

| Component | What It Does | Replaces in Our Setup |
|---|---|---|
| Prometheus Operator | Auto-discovers services to scrape | Manual `prometheus.yml` |
| Grafana | Dashboards with pre-built K8s views | Our manual Grafana setup |
| Alertmanager | Routes alerts to Slack/PagerDuty/email | Nothing (we didn't have this) |
| Node Exporter | Host-level metrics (CPU, disk, network) | Nothing |
| kube-state-metrics | K8s object metrics (pod restarts, etc.) | Nothing |

### 7.2 Installation

```bash
helm repo add prometheus-community \
  https://prometheus-community.github.io/helm-charts

helm install monitoring prometheus-community/kube-prometheus-stack \
  --namespace monitoring --create-namespace \
  --set grafana.adminPassword=admin \
  --set prometheus.prometheusSpec.serviceMonitorSelectorNilUsesHelmValues=false
```

The last flag is critical: it tells Prometheus to discover ServiceMonitors from ALL
namespaces, not just the monitoring namespace.

### 7.3 Auto-Discovery with ServiceMonitor

**Current approach** (Docker Compose): We list every service in `prometheus.yml`:
```yaml
scrape_configs:
  - job_name: gateway-service
    scheme: https
    tls_config:
      insecure_skip_verify: true
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['gateway-service:8082']
```

**Kubernetes approach:** Instead of listing targets, we create a `ServiceMonitor` that
tells Prometheus to auto-discover any Service with specific labels:

```yaml
# Included in the spring-service Helm chart
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: {{ include "spring-service.fullname" . }}
  labels:
    {{- include "spring-service.labels" . | nindent 4 }}
spec:
  selector:
    matchLabels:
      {{- include "spring-service.selectorLabels" . | nindent 6 }}
  endpoints:
    - port: http
      path: /actuator/prometheus
      interval: 15s
      scheme: https
      tlsConfig:
        insecureSkipVerify: true           # Self-signed certs
```

**The magic:** When you deploy a new service with the `spring-service` chart,
Prometheus automatically starts scraping it. No config file changes needed.

### 7.4 Distributed Tracing — Tempo Replaces Zipkin

In production Kubernetes, **Grafana Tempo** is preferred over Zipkin because:
- It stores traces in object storage (S3/GCS) — virtually unlimited retention
- Native Grafana integration — traces, metrics, and logs in one UI
- Compatible with OpenTelemetry (which our services already use)

```bash
helm install tempo grafana/tempo \
  --namespace monitoring \
  --set tempo.storage.trace.backend=s3
```

Our services don't need any code changes — they already send spans via OpenTelemetry.
Just change the endpoint in the ConfigMap:

```yaml
# Before (Zipkin)
ZIPKIN_ENDPOINT: http://zipkin:9411/api/v2/spans

# After (Tempo with Zipkin-compatible receiver)
ZIPKIN_ENDPOINT: http://tempo.monitoring:9411/api/v2/spans
```

### 7.5 Centralized Logging — Loki

Currently, our services write structured JSON logs (via `logstash-logback-encoder`)
to stdout. In Kubernetes, we add **Grafana Loki** to collect and query these logs:

```bash
helm install loki grafana/loki-stack \
  --namespace monitoring \
  --set promtail.enabled=true              # DaemonSet that tails container logs
  --set loki.persistence.enabled=true
```

**How it works:**
1. Our services log JSON to stdout (already done)
2. Kubernetes stores container stdout in `/var/log/containers/`
3. Promtail (DaemonSet on every node) tails these log files
4. Promtail ships logs to Loki
5. Grafana queries Loki with LogQL (similar to PromQL)

**Example Grafana query:**
```
{namespace="smarthome", app="gateway-service"} | json | level="ERROR"
```

### 7.6 The Unified Observability Stack

```
                    ┌──────────────────────┐
                    │       Grafana        │ ← Single pane of glass
                    │   Dashboards + Alerts│
                    └──┬──────┬──────┬─────┘
                       │      │      │
              ┌────────┘      │      └────────┐
              ▼               ▼               ▼
     ┌────────────────┐ ┌──────────┐ ┌────────────────┐
     │   Prometheus   │ │  Tempo   │ │     Loki       │
     │   (Metrics)    │ │ (Traces) │ │    (Logs)      │
     └───────▲────────┘ └────▲─────┘ └───────▲────────┘
             │               │               │
    ServiceMonitor     OTLP/Zipkin      Promtail
    auto-discovery      spans            DaemonSet
             │               │               │
     ┌───────┴───────────────┴───────────────┴───────┐
     │              Our 8 Spring Boot Pods           │
     │   /actuator/prometheus  │  OTLP spans  │ JSON │
     │          (metrics)      │  (traces)    │(logs)│
     └───────────────────────────────────────────────┘
```

---

## 8. Migration Roadmap

### Recommended Implementation Order

```
Week 1-2: Foundation
├── Set up a Kubernetes cluster (minikube for local, or EKS/GKE/AKS)
├── Install Helm and learn basic commands
├── Create the spring-service sub-chart (templates)
├── Deploy ONE service (iam-service) to verify the pattern
└── Milestone: iam-service running in K8s with kubectl port-forward

Week 3-4: Stateful Services
├── Install RabbitMQ Cluster Operator, deploy 3-node cluster
├── Install CloudNativePG Operator, deploy TimescaleDB
├── Deploy Redis via Bitnami Helm chart
├── Migrate definitions.json and DB init scripts
└── Milestone: All infrastructure running in K8s

Week 5-6: Application Services
├── Deploy all 8 services using the spring-service chart
├── Install cert-manager, migrate from generate-certs.sh
├── Set up Ingress (NGINX Ingress Controller) for api-gateway
├── Configure HPA for gateway-service and processing-service
└── Milestone: Full stack running in K8s, accessible via Ingress

Week 7-8: Security & Secrets
├── Install Vault (Helm chart) and Vault Agent Injector
├── Migrate all credentials from K8s Secrets to Vault
├── Enable Vault database engine for dynamic DB credentials
├── Enable Vault PKI engine for automatic cert rotation
└── Milestone: Zero hardcoded credentials anywhere

Week 9-10: CI/CD & Observability
├── Set up GitHub Actions workflow (lint → test → build → scan → deploy)
├── Install kube-prometheus-stack
├── Install Tempo and Loki
├── Create Grafana dashboards for our custom metrics
├── Set up Alertmanager rules (pod restarts, error rate, latency p99)
└── Milestone: Fully automated pipeline with monitoring

Week 11-12: Hardening & Load Testing
├── Add NetworkPolicies (restrict pod-to-pod communication)
├── Add PodDisruptionBudgets (ensure availability during upgrades)
├── Optional: Install Istio for service mesh mTLS (replaces app-level mTLS)
├── Load test with k6: simulate 10K sensors at 1 reading/sec
├── Tune resource limits based on observed metrics
└── Milestone: Production-ready deployment
```

### What Changes in Our Code?

Almost nothing. The beauty of the 12-factor app approach we've been following is that
our services are already Kubernetes-ready:

| Concern | Already Done | K8s Change |
|---|---|---|
| Config via env vars | ✅ `${VAR:default}` pattern | ConfigMap/Secret replaces docker-compose env |
| Health endpoints | ✅ `/actuator/health` | Add readiness/liveness group config |
| Structured logging | ✅ JSON to stdout | Promtail collects automatically |
| Metrics endpoint | ✅ `/actuator/prometheus` | ServiceMonitor replaces prometheus.yml |
| Tracing | ✅ OpenTelemetry to Zipkin | Change endpoint URL to Tempo |
| Stateless services | ✅ No local file storage | Pods can be killed/restarted freely |
| TLS certificates | ✅ `server.ssl.*` from env vars | cert-manager generates certs |
| Non-root user | ✅ `USER spring` in Dockerfile | Passes K8s PodSecurityPolicy |

The only code change needed is adding health probe groups to `application.yml`:

```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include: db, rabbit, redis
        liveness:
          include: livenessState
```

---

## Quick Reference — Key Commands

```bash
# === Cluster Setup ===
minikube start --cpus=4 --memory=8192 --driver=docker

# === Helm ===
helm install smarthome ./helm/smarthome -n smarthome --create-namespace
helm upgrade smarthome ./helm/smarthome -n smarthome
helm rollback smarthome 1 -n smarthome
helm uninstall smarthome -n smarthome

# === Debugging ===
kubectl get pods -n smarthome
kubectl logs -f deployment/gateway-service -n smarthome
kubectl describe pod gateway-service-xxx -n smarthome
kubectl exec -it gateway-service-xxx -n smarthome -- /bin/sh
kubectl port-forward svc/api-gateway 8080:8080 -n smarthome

# === Scaling ===
kubectl scale deployment gateway-service --replicas=5 -n smarthome
kubectl autoscale deployment gateway-service \
  --cpu-percent=70 --min=2 --max=10 -n smarthome

# === Secrets ===
kubectl create secret generic gateway-secrets \
  --from-literal=RABBITMQ_PASSWORD=xxx -n smarthome
kubectl get secrets -n smarthome
```
