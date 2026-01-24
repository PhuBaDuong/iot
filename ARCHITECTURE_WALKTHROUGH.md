# IoT Smart Home Monitor — Architecture Walkthrough

> A hands-on technical guide to the event-driven microservices architecture, message routing, and Spring Boot implementation of the IoT Smart Home Monitor system.

---

## Table of Contents

1. [System Overview](#1-system-overview)
2. [Infrastructure Setup](#2-infrastructure-setup)
3. [End-to-End Message Routing Logic](#3-end-to-end-message-routing-logic)
4. [Spring Boot Code Implementation Analysis](#4-spring-boot-code-implementation-analysis)
5. [Verification and Pattern Validation](#5-verification-and-pattern-validation)

---

## 1. System Overview

The IoT Smart Home Monitor is a multi-module Maven project (Spring Boot 4.0.1, Java 25) composed of four modules:

| Module | Role | Port |
|---|---|---|
| `iot-common` | Shared library — DTOs (`SensorReading`), events (`AlertEvent`, `SensorDataEvent`), and constants (`RabbitMQConstants`) | N/A |
| `sensor-simulator-service` | **Producer** — generates simulated sensor readings and publishes them to RabbitMQ | 8081 |
| `gateway-service` | **Intermediary** — consumes raw readings, validates, detects anomalies, and re-publishes to downstream queues | 8082 |
| `processing-service` | **Consumer** — consumes processed data and alert events for analytics and notification handling | 8083 |

Communication is fully asynchronous via **RabbitMQ topic exchanges**. There are no synchronous HTTP calls between services.

```
┌─────────────────────┐
│ sensor-simulator    │
│     (Producer)      │
└────────┬────────────┘
         │ publishes SensorReading
         │ routing key: sensor.{type}.{location}
         ▼
┌─────────────────────┐    binding: "sensor.#"
│  sensor.exchange    │──────────────────────────┐
│  (TopicExchange)    │                          │
└─────────────────────┘                          ▼
                                      ┌─────────────────────┐
                                      │ sensor.readings.queue│
                                      └────────┬────────────┘
                                               │ @RabbitListener
                                               ▼
                                      ┌─────────────────────┐
                                      │   gateway-service    │
                                      │  (Validate + Route)  │
                                      └───┬────────────┬─────┘
                    ┌─────────────────────┘            └──────────────────────┐
                    │ publishProcessedData()                publishAlert()    │
                    │ routing key: data.processed           routing key:      │
                    │ exchange: sensor.exchange              alert.anomaly    │
                    │                                       exchange:         │
                    ▼                                       alerts.exchange   │
         ┌─────────────────────┐                           ▼                 │
         │ processed.data.queue│                ┌─────────────────────┐      │
         └────────┬────────────┘                │    alerts.queue     │      │
                  │ @RabbitListener              └────────┬────────────┘      │
                  ▼                                       │ @RabbitListener   │
         ┌─────────────────────┐                          ▼                  │
         │ProcessedDataListener│                ┌─────────────────────┐      │
         │ (AnalyticsService)  │                │   AlertListener     │      │
         └─────────────────────┘                │(AlertHandlerService)│      │
                                                └─────────────────────┘      │
```

---

## 2. Infrastructure Setup

### 2.1 Launching RabbitMQ with Docker Compose

The project provides a `docker-compose.yml` at the repository root that spins up a single RabbitMQ 3.12 container with the management plugin pre-installed.

**Start the broker:**

```bash
docker compose up -d
```

This creates a container named `smarthome-rabbitmq` exposing two ports:

| Port | Protocol | Purpose |
|---|---|---|
| `5672` | AMQP | Used by Spring Boot services to connect |
| `15672` | HTTP | RabbitMQ Management UI |

**Key docker-compose.yml configuration:**

```yaml
services:
  rabbitmq:
    image: rabbitmq:3.12-management
    container_name: smarthome-rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: smarthome
      RABBITMQ_DEFAULT_PASS: smarthome123
    volumes:
      - rabbitmq_data:/var/lib/rabbitmq
    healthcheck:
      test: rabbitmq-diagnostics -q ping
      interval: 30s
      timeout: 10s
      retries: 5
```

The `RABBITMQ_DEFAULT_USER` and `RABBITMQ_DEFAULT_PASS` environment variables create the initial broker credentials. The named volume `rabbitmq_data` ensures queue definitions and persisted messages survive container restarts. The health check polls the broker every 30 seconds using the built-in `rabbitmq-diagnostics` tool.

### 2.2 Accessing the Management UI

Once the container is healthy, open the Management UI:

```
URL:      http://localhost:15672
Username: smarthome
Password: smarthome123
```

The Management UI lets you inspect exchanges, queues, bindings, message rates, and consumer counts — all critical for the verification steps in Section 5.

### 2.3 Verifying the Broker Is Ready

```bash
docker compose ps          # Status should be "healthy"
docker compose logs rabbitmq   # Look for "Server startup complete"
```

All three Spring Boot services reference the same credentials in their `application.yml`:

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: smarthome
    password: smarthome123
```

---

## 3. End-to-End Message Routing Logic

This section traces the complete lifecycle of a `SensorReading` from creation to terminal consumption.

### 3.1 Central Constants — `RabbitMQConstants.java`

All exchange, queue, and routing key names are defined once in the shared `iot-common` module to provide a single source of truth:

```java
// iot-common/.../constants/RabbitMQConstants.java

// Exchanges
public static final String SENSOR_EXCHANGE    = "sensor.exchange";
public static final String ALERTS_EXCHANGE    = "alerts.exchange";

// Queues
public static final String SENSOR_READINGS_QUEUE = "sensor.readings.queue";
public static final String PROCESSED_DATA_QUEUE  = "processed.data.queue";
public static final String ALERTS_QUEUE           = "alerts.queue";

// Routing Keys
public static final String SENSOR_ROUTING_KEY_PATTERN = "sensor.#";
public static final String SENSOR_ROUTING_KEY_FORMAT  = "sensor.%s.%s";
public static final String PROCESSED_ROUTING_KEY      = "data.processed";
public static final String ALERT_ROUTING_KEY          = "alert.anomaly";
```

Centralizing these constants prevents mismatched strings across services. Every producer and consumer imports from this single class.

### 3.2 Stage 1 — Sensor Reading Creation (sensor-simulator-service)

The `SensorSimulatorService` generates readings on a configurable schedule (default: every 2 seconds). Seven sensors are defined in `application.yml`:

| Sensor ID | Type | Location |
|---|---|---|
| `temp-sensor-001` | TEMPERATURE | living-room |
| `temp-sensor-002` | TEMPERATURE | bedroom |
| `humidity-sensor-001` | HUMIDITY | living-room |
| `humidity-sensor-002` | HUMIDITY | bathroom |
| `motion-sensor-001` | MOTION | entrance |
| `light-sensor-001` | LIGHT | living-room |
| `pressure-sensor-001` | PRESSURE | outdoor |

Each `SensorReading` DTO contains:

```java
public class SensorReading {
    private String readingId;       // UUID
    private String sensorId;        // e.g., "temp-sensor-001"
    private SensorType sensorType;  // TEMPERATURE, HUMIDITY, MOTION, LIGHT, PRESSURE
    private Double value;           // e.g., 22.45
    private String unit;            // e.g., "°C"
    private String location;        // e.g., "living-room"
    private Instant timestamp;
    private Map<String, Object> metadata;  // firmwareVersion, batteryLevel
}
```

### 3.3 Stage 2 — Publishing to the Topic Exchange

The simulator constructs a **routing key** using `SENSOR_ROUTING_KEY_FORMAT`:

```java
String routingKey = String.format(
    RabbitMQConstants.SENSOR_ROUTING_KEY_FORMAT,  // "sensor.%s.%s"
    sensor.getType().getName(),                    // e.g., "temperature"
    sensor.getLocation()                           // e.g., "living-room"
);
// Result: "sensor.temperature.living-room"
```

It then publishes to the `sensor.exchange` topic exchange:

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,  // "sensor.exchange"
    routingKey,                         // "sensor.temperature.living-room"
    reading                             // SensorReading (serialized to JSON)
);
```

**How topic routing works:** The `sensor.readings.queue` is bound to `sensor.exchange` with the pattern `sensor.#` (defined by `SENSOR_ROUTING_KEY_PATTERN`). The `#` wildcard matches zero or more dot-delimited words, so *every* routing key starting with `sensor.` lands in this queue:

```
sensor.temperature.living-room  →  matches "sensor.#"  ✓
sensor.humidity.bathroom        →  matches "sensor.#"  ✓
sensor.motion.entrance          →  matches "sensor.#"  ✓
alert.temperature.high          →  matches "sensor.#"  ✗ (different prefix)
```

### 3.4 Stage 3 — Gateway Service Processing

The `gateway-service` acts as the **intermediary** between raw sensor data and downstream consumers. Its `SensorDataListener` consumes from `sensor.readings.queue`:

```java
@RabbitListener(queues = RabbitMQConstants.SENSOR_READINGS_QUEUE)
public void handleSensorReading(SensorReading reading) { ... }
```

The processing pipeline inside `handleSensorReading` has four steps:

#### Step 1: Validation via `ValidationService`

```java
ValidationResult validationResult = validationService.validate(reading);
```

The `ValidationService` checks:
- **Required fields**: `readingId`, `sensorId`, `sensorType`, `value`, `location`, `timestamp`
- **Value ranges**: value must fall within the `SensorType`'s physical range (e.g., TEMPERATURE: -40.0 to 85.0 °C)
- **Timestamp sanity**: not in the future (1-minute tolerance for clock skew), not older than 24 hours

It returns a `ValidationResult` record containing a `valid` boolean and a list of error strings. Invalid readings are still published downstream (marked as `valid=false`) so they can be tracked.

#### Step 2: Enrichment into `SensorDataEvent`

```java
SensorDataEvent event = createSensorDataEvent(reading, validationResult, correlationId);
```

The raw `SensorReading` is wrapped in a `SensorDataEvent` that adds processing metadata:

```java
public class SensorDataEvent {
    private SensorReading reading;
    private Instant processedAt;
    private String processedBy;       // "gateway-service"
    private boolean valid;
    private String validationError;
    private boolean anomaly;
    private String anomalyDescription;
    private String correlationId;     // UUID for end-to-end tracing
}
```

#### Step 3: Anomaly Detection

```java
Optional<AnomalyDetails> anomalyOpt = anomalyDetectionService.detectAnomaly(reading);
```

If the reading's value exceeds the configured thresholds (defined in `gateway-service/application.yml`), the gateway publishes an `AlertEvent` to the **alerts exchange**:

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.ALERTS_EXCHANGE,   // "alerts.exchange"
    RabbitMQConstants.ALERT_ROUTING_KEY, // "alert.anomaly"
    alert                                // AlertEvent object
);
```

The `alerts.queue` is bound to `alerts.exchange` with the routing key `alert.anomaly`, so alerts are routed there.

Configured anomaly thresholds:

| Sensor Type | Normal Min | Normal Max |
|---|---|---|
| TEMPERATURE | 15.0 °C | 30.0 °C |
| HUMIDITY | 30.0 % | 70.0 % |
| LIGHT | 0.0 lux | 10,000.0 lux |
| PRESSURE | 950.0 hPa | 1,050.0 hPa |

#### Step 4: Publishing to the Processed Data Queue

Regardless of anomaly status, the enriched `SensorDataEvent` is published to the `PROCESSED_DATA_QUEUE`:

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,       // "sensor.exchange"
    RabbitMQConstants.PROCESSED_ROUTING_KEY, // "data.processed"
    event                                    // SensorDataEvent
);
```

The `processed.data.queue` is bound to `sensor.exchange` with the routing key `data.processed`, giving it an exact match.

### 3.5 Stage 4 — Terminal Consumption (processing-service)

The `processing-service` hosts two independent listeners:

**ProcessedDataListener** — consumes from `processed.data.queue`:

```java
@RabbitListener(queues = RabbitMQConstants.PROCESSED_DATA_QUEUE)
public void handleProcessedData(SensorDataEvent event) {
    analyticsService.updateWithEvent(event);
}
```

Delegates to `AnalyticsService` for real-time metrics aggregation (per-sensor statistics, rolling averages).

**AlertListener** — consumes from `alerts.queue`:

```java
@RabbitListener(queues = RabbitMQConstants.ALERTS_QUEUE)
public void handleAlert(AlertEvent alertEvent) {
    alertHandlerService.handleAlert(alertEvent);
}
```

Delegates to `AlertHandlerService` for alert processing and notification handling. Exceptions are re-thrown to trigger the retry mechanism.

### 3.6 Complete Routing Topology Summary

| Exchange | Type | Queue | Binding Key | Publisher | Consumer |
|---|---|---|---|---|---|
| `sensor.exchange` | Topic | `sensor.readings.queue` | `sensor.#` | `SensorSimulatorService` | `SensorDataListener` (gateway) |
| `sensor.exchange` | Topic | `processed.data.queue` | `data.processed` | `SensorDataListener` (gateway) | `ProcessedDataListener` (processing) |
| `alerts.exchange` | Topic | `alerts.queue` | `alert.anomaly` | `SensorDataListener` (gateway) | `AlertListener` (processing) |

---

## 4. Spring Boot Code Implementation Analysis

### 4.1 RabbitMQConfig Across Services

Each service declares only the infrastructure it needs. Spring AMQP's queue/exchange declarations are **idempotent** — declaring an already-existing queue with the same configuration is a safe no-op.

#### sensor-simulator-service — Producer Config

Declares the producer-side infrastructure:

```java
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange sensorExchange() {
        return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
        //                       name                              durable  auto-delete
    }

    @Bean
    public Queue sensorReadingsQueue() {
        return new Queue(RabbitMQConstants.SENSOR_READINGS_QUEUE, true);
        //               name                                     durable
    }

    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue, TopicExchange sensorExchange) {
        return BindingBuilder
                .bind(sensorReadingsQueue)
                .to(sensorExchange)
                .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN); // "sensor.#"
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
```

**Key design choice:** The producer also declares the queue and binding to guarantee the infrastructure exists before publishing. If the queue didn't exist when the first message was sent, RabbitMQ would discard the message (topic exchanges don't error on unroutable messages by default).

#### gateway-service — Full Topology Config

The gateway declares the **complete routing topology** since it both consumes and produces:

```java
@Configuration
public class RabbitMQConfig {

    // Two Topic Exchanges
    @Bean
    public TopicExchange sensorExchange() {
        return ExchangeBuilder.topicExchange(RabbitMQConstants.SENSOR_EXCHANGE).durable(true).build();
    }

    @Bean
    public TopicExchange alertsExchange() {
        return ExchangeBuilder.topicExchange(RabbitMQConstants.ALERTS_EXCHANGE).durable(true).build();
    }

    // Three Durable Queues
    @Bean
    public Queue sensorReadingsQueue() {
        return QueueBuilder.durable(RabbitMQConstants.SENSOR_READINGS_QUEUE).build();
    }

    @Bean
    public Queue processedDataQueue() {
        return QueueBuilder.durable(RabbitMQConstants.PROCESSED_DATA_QUEUE).build();
    }

    @Bean
    public Queue alertsQueue() {
        return QueueBuilder.durable(RabbitMQConstants.ALERTS_QUEUE).build();
    }

    // Three Bindings
    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue, TopicExchange sensorExchange) {
        return BindingBuilder.bind(sensorReadingsQueue).to(sensorExchange)
                .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN); // "sensor.#"
    }

    @Bean
    public Binding processedDataBinding(Queue processedDataQueue, TopicExchange sensorExchange) {
        return BindingBuilder.bind(processedDataQueue).to(sensorExchange)
                .with(RabbitMQConstants.PROCESSED_ROUTING_KEY); // "data.processed"
    }

    @Bean
    public Binding alertsBinding(Queue alertsQueue, TopicExchange alertsExchange) {
        return BindingBuilder.bind(alertsQueue).to(alertsExchange)
                .with(RabbitMQConstants.ALERT_ROUTING_KEY); // "alert.anomaly"
    }

    // Jackson converter with JavaTimeModule for Instant serialization
    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        return template;
    }
}
```

**Note:** The gateway's `jsonMessageConverter()` explicitly registers the `JavaTimeModule` on a custom `ObjectMapper`, ensuring `Instant` fields (like `SensorReading.timestamp`) are properly serialized. The other services rely on the default `Jackson2JsonMessageConverter()` constructor which also handles standard types.

#### processing-service — Consumer-Only Config

The processing service declares only the queues it consumes from (no exchanges or bindings):

```java
@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue processedDataQueue() {
        return new Queue(RabbitMQConstants.PROCESSED_DATA_QUEUE, true);
    }

    @Bean
    public Queue alertsQueue() {
        return new Queue(RabbitMQConstants.ALERTS_QUEUE, true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter jsonMessageConverter) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(jsonMessageConverter);
        return rabbitTemplate;
    }
}
```

It doesn't need to declare exchanges or bindings because the gateway-service already creates them. Queue declarations are repeated here to ensure the queues exist even if the processing-service starts before the gateway.

### 4.2 Message Publishing with `rabbitTemplate.convertAndSend()`

The `convertAndSend()` method performs three operations atomically:

1. **Convert** — the `Jackson2JsonMessageConverter` serializes the Java object to a JSON byte array
2. **Wrap** — creates an AMQP `Message` with the JSON payload plus headers (content type, type ID for deserialization)
3. **Send** — publishes to the specified exchange with the given routing key

Two publishing call sites exist:

**In `SensorSimulatorService.publishReading()`:**

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,   // exchange
    routingKey,                          // dynamic: "sensor.temperature.living-room"
    reading                              // SensorReading object
);
```

**In `SensorDataListener.publishProcessedData()`:**

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,
    RabbitMQConstants.PROCESSED_ROUTING_KEY,  // static: "data.processed"
    event                                      // SensorDataEvent object
);
```

**In `SensorDataListener.publishAlert()`:**

```java
rabbitTemplate.convertAndSend(
    RabbitMQConstants.ALERTS_EXCHANGE,
    RabbitMQConstants.ALERT_ROUTING_KEY,  // static: "alert.anomaly"
    alert                                  // AlertEvent object
);
```

Note the pattern: the simulator uses a **dynamic** routing key (sensor type + location), while the gateway uses **static** routing keys for its downstream publishing.

### 4.3 Asynchronous Consumption with `@RabbitListener`

The `@RabbitListener` annotation turns a method into an asynchronous message consumer. Spring AMQP handles:

| Concern | Behavior |
|---|---|
| Connection | Managed by `ConnectionFactory`; auto-reconnects on failure |
| Deserialization | `Jackson2JsonMessageConverter` converts JSON → Java object |
| Threading | Listener runs in a dedicated consumer thread pool |
| Acknowledgment | AUTO mode — message acked on successful return, nacked on exception |
| Retry | Configurable in `application.yml` (max attempts, backoff multiplier) |

**Three `@RabbitListener` methods in the system:**

1. **`SensorDataListener.handleSensorReading()`** (gateway-service)
   - Queue: `RabbitMQConstants.SENSOR_READINGS_QUEUE`
   - Input type: `SensorReading`
   - Orchestrates: validation → anomaly detection → publish processed data + optional alert

2. **`ProcessedDataListener.handleProcessedData()`** (processing-service)
   - Queue: `RabbitMQConstants.PROCESSED_DATA_QUEUE`
   - Input type: `SensorDataEvent`
   - Delegates to `AnalyticsService.updateWithEvent()`

3. **`AlertListener.handleAlert()`** (processing-service)
   - Queue: `RabbitMQConstants.ALERTS_QUEUE`
   - Input type: `AlertEvent`
   - Delegates to `AlertHandlerService.handleAlert()`
   - Re-throws exceptions to trigger retry

The processing-service configures consumer concurrency in `application.yml`:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        concurrency: 1       # Initial consumer threads per listener
        max-concurrency: 5    # Auto-scales up to 5 threads under load
        prefetch: 10          # Each consumer fetches 10 messages at a time
        retry:
          enabled: true
          initial-interval: 1000
          max-attempts: 3
          max-interval: 10000
          multiplier: 2.0
```

---

## 5. Verification and Pattern Validation

### 5.1 Running the Services

**Prerequisites:** Docker running, Java 25 installed.

**Step 1 — Start RabbitMQ:**

```bash
docker compose up -d
# Wait for healthy status:
docker compose ps
```

**Step 2 — Build the project:**

```bash
./mvnw clean install -DskipTests
```

**Step 3 — Start each service** (in separate terminals, from the repository root):

```bash
# Terminal 1: Gateway (start first — declares the full topology)
cd gateway-service
../mvnw spring-boot:run

# Terminal 2: Processing Service
cd processing-service
../mvnw spring-boot:run

# Terminal 3: Sensor Simulator (start last — begins publishing immediately)
cd sensor-simulator-service
../mvnw spring-boot:run
```

Starting the gateway first ensures all exchanges, queues, and bindings are created before any messages flow.

### 5.2 Observing Messages in the Management Console

Open `http://localhost:15672` (credentials: `smarthome`/`smarthome123`).

#### Queues Tab

Navigate to **Queues and Streams**. You should see three queues:

| Queue | Expected Behavior |
|---|---|
| `sensor.readings.queue` | Messages arrive every ~2 seconds (7 sensors × 1 batch). Should stay near 0 depth if the gateway is consuming. |
| `processed.data.queue` | Receives one `SensorDataEvent` per sensor reading from the gateway. Should stay near 0 depth if the processing-service is consuming. |
| `alerts.queue` | Receives `AlertEvent` messages only when anomalies are detected. Activity depends on how often sensor values exceed the configured thresholds. |

Click on any queue to see:
- **Message rates** (publish/deliver per second)
- **Consumer count** (how many `@RabbitListener` instances are connected)
- **Queue depth** (unacknowledged messages indicate slow consumers)

#### Exchanges Tab

Navigate to **Exchanges**. You should see:

| Exchange | Type | Bindings |
|---|---|---|
| `sensor.exchange` | topic | `sensor.readings.queue` via `sensor.#`, `processed.data.queue` via `data.processed` |
| `alerts.exchange` | topic | `alerts.queue` via `alert.anomaly` |

Click on `sensor.exchange` to see its bindings and confirm the routing key patterns.

### 5.3 Verifying the Topic Exchange Pattern

The topic exchange pattern is the core routing mechanism. To verify it works:

1. **Observe varied routing keys in logs.** Set log level to `DEBUG` for the simulator:

   ```yaml
   logging:
     level:
       com.smarthome: DEBUG
   ```

   You'll see log lines like:
   ```
   Published reading: temp-sensor-001 -> sensor.temperature.living-room = 22.45 °C
   Published reading: humidity-sensor-001 -> sensor.humidity.living-room = 48.2 %
   Published reading: motion-sensor-001 -> sensor.motion.entrance = 0.0 boolean
   ```

   All these different routing keys match the `sensor.#` binding pattern and arrive at `sensor.readings.queue`.

2. **Verify in the Management UI.** On the `sensor.exchange` detail page, use the **Publish message** section to manually send a test message with routing key `sensor.test.debug`. Then check `sensor.readings.queue` — the message will be there because `sensor.#` matches `sensor.test.debug`.

3. **Negative test.** Publish a message to `sensor.exchange` with routing key `alert.test` — it will **not** arrive in `sensor.readings.queue` because `alert.test` doesn't match `sensor.#`. This confirms the pattern-based filtering.

### 5.4 Verifying the Competing Consumers Pattern

The **competing consumers** pattern allows horizontal scaling: multiple consumer instances share the same queue, and RabbitMQ distributes messages round-robin.

**To verify:**

1. **Start a second instance of the processing-service** on a different port:

   ```bash
   cd processing-service
   ../mvnw spring-boot:run -Dspring-boot.run.arguments="--server.port=8084"
   ```

2. **Check the Management UI.** Navigate to `processed.data.queue` and look at the **Consumers** section. You should now see **2 consumers** (one from each instance).

3. **Observe round-robin distribution.** Each instance's logs will show roughly half the messages. With 7 sensors publishing every 2 seconds, each instance processes ~3-4 messages per cycle.

4. **Scale further.** Add a third instance on port 8085 — the messages redistribute across all three instances automatically. RabbitMQ guarantees that **each message is delivered to exactly one consumer**.

5. **Verify with prefetch.** The `prefetch: 10` setting means each consumer pulls up to 10 messages at a time from the broker. Lower this to `1` to see more perfectly even distribution (at the cost of throughput):

   ```yaml
   spring:
     rabbitmq:
       listener:
         simple:
           prefetch: 1
   ```

---

## Summary of Key Patterns

| Pattern | Where Used | How to Verify |
|---|---|---|
| **Topic Exchange** | `sensor.exchange` routes by `sensor.{type}.{location}` | Observe varied routing keys all landing in `sensor.readings.queue` |
| **Competing Consumers** | Multiple `processing-service` instances share `processed.data.queue` | Scale instances and check consumer count in Management UI |
| **Message Enrichment** | Gateway wraps `SensorReading` into `SensorDataEvent` with metadata | Compare raw queue messages vs. processed queue messages |
| **Content-Based Routing** | Gateway routes anomalies to `alerts.queue`, all data to `processed.data.queue` | Observe `alerts.queue` activity only during threshold breaches |
| **Correlation IDs** | UUID assigned per message in gateway for end-to-end tracing | Grep logs for a specific `correlationId` across services |
| **Durable Queues** | All three queues declared as durable | Restart RabbitMQ (`docker compose restart`) — queues survive |
| **Retry with Backoff** | Processing-service: 3 attempts, 1-10s exponential backoff | Throw an exception in a listener and observe retry behavior in logs |
