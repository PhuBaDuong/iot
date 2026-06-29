# Service-by-Service Implementation Guide

> A beginner-friendly deep-dive into every microservice in the IoT Smart Home Monitor project.
> Each service section explains the Spring Boot fundamentals, RabbitMQ wiring, and core business logic
> so you can understand *why* the code is written the way it is — not just *what* it does.

---

## Table of Contents

- [Shared Foundation — The `iot-common` Module](#shared-foundation--the-iot-common-module)
- [Understanding RabbitMQ Components](#understanding-rabbitmq-components)
- [Service 1: sensor-simulator-service](#service-1-sensor-simulator-service)
- [Service 2: gateway-service](#service-2-gateway-service)
- [Service 3: processing-service](#service-3-processing-service)
- [Cross-Service Annotation Reference](#cross-service-annotation-reference)

---

## Shared Foundation — The `iot-common` Module

Before we examine each service, it is important to understand the **shared library** they all depend on.
The `iot-common` module contains no runnable application — it is packaged as a plain JAR and listed as a
`<dependency>` in every service's `pom.xml`. It holds three categories of shared code:

| Package | Contents | Why Shared |
|---|---|---|
| `com.smarthome.common.dto` | `SensorReading`, `SensorType` | Every service must agree on the shape of sensor data. A single DTO definition prevents serialization mismatches. |
| `com.smarthome.common.event` | `SensorDataEvent`, `AlertEvent` | Event objects that flow between services over RabbitMQ. Sharing them ensures producers and consumers deserialize identically. |
| `com.smarthome.common.constants` | `RabbitMQConstants` | Exchange names, queue names, and routing keys live here so a typo in one service cannot silently break routing. |

Throughout this guide, whenever you see an import starting with `com.smarthome.common.*`, it comes from
this shared module.

---

## Understanding RabbitMQ Components

Before we walk through each service's code, you need a solid mental model of the four RabbitMQ
building blocks that this project relies on. Every exchange declaration, queue definition, and
binding you will encounter in the service-level `RabbitMQConfig.java` files maps directly to one of
these concepts. Understanding them here — once — will make every subsequent code section click.

> **How to read this section:** Each sub-section starts with a plain-language definition, then shows
> the exact code from this project that creates the component, and finishes with the architectural
> reason it exists. If you are already familiar with RabbitMQ, skim the definitions and focus on the
> project-specific justifications.

### Topic Exchanges

#### What Is a Topic Exchange?

An exchange is a **message router** inside RabbitMQ. Producers never send messages directly to
queues — they send to an exchange, and the exchange decides which queue(s) should receive each
message. RabbitMQ offers several exchange types (direct, fanout, headers), but this project uses
**topic exchanges** exclusively.

A topic exchange routes messages by comparing the **routing key** attached to each message against
**binding patterns** registered by queues. Patterns use two special wildcard characters:

| Wildcard | Meaning | Example Pattern | Matches |
|---|---|---|---|
| `*` (star) | Exactly **one** dot-delimited word | `sensor.temperature.*` | `sensor.temperature.living-room` ✓, `sensor.temperature.bedroom` ✓, `sensor.humidity.bathroom` ✗ |
| `#` (hash) | **Zero or more** dot-delimited words | `sensor.#` | `sensor.temperature.living-room` ✓, `sensor.humidity.bathroom` ✓, `sensor` ✓ |

This makes topic exchanges far more flexible than direct exchanges (which require an exact match)
and more selective than fanout exchanges (which broadcast to every bound queue indiscriminately).

#### How This Project Uses Topic Exchanges

The project defines two topic exchanges in `RabbitMQConstants.java`:

```java
public static final String SENSOR_EXCHANGE = "sensor.exchange";
public static final String ALERTS_EXCHANGE = "alerts.exchange";
```

These are instantiated as Spring beans in each service's `RabbitMQConfig.java`. The two creation
styles you will see are functionally identical — they simply reflect different Spring AMQP APIs:

**In `sensor-simulator-service` (constructor style):**

```java
@Bean
public TopicExchange sensorExchange() {
    return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
    //                       name: "sensor.exchange"   durable  auto-delete
}
```

**In `gateway-service` (builder style):**

```java
@Bean
public TopicExchange sensorExchange() {
    return ExchangeBuilder
            .topicExchange(RabbitMQConstants.SENSOR_EXCHANGE)
            .durable(true)
            .build();
}

@Bean
public TopicExchange alertsExchange() {
    return ExchangeBuilder
            .topicExchange(RabbitMQConstants.ALERTS_EXCHANGE)
            .durable(true)
            .build();
}
```

Both approaches create a durable topic exchange — one that survives broker restarts.

#### Why This Project Needs Topic Exchanges

The IoT Smart Home Monitor has **seven sensors** spanning five types (temperature, humidity, motion,
light, pressure) deployed across multiple locations (living-room, bedroom, bathroom, entrance,
outdoor). A single `sensor.exchange` with topic routing lets the project handle all of them with
one exchange instead of needing a separate exchange per sensor type:

```
┌─────────────────────────────────────────────────────────────────┐
│                      sensor.exchange                            │
│                      (TopicExchange)                             │
│                                                                 │
│  Incoming routing keys:              Binding patterns:          │
│                                                                 │
│  sensor.temperature.living-room ──┐                             │
│  sensor.temperature.bedroom ──────┤                             │
│  sensor.humidity.living-room ─────┤  "sensor.#"                 │
│  sensor.humidity.bathroom ────────┼──→ sensor.readings.queue    │
│  sensor.motion.entrance ──────────┤                             │
│  sensor.light.living-room ────────┤                             │
│  sensor.pressure.outdoor ─────────┘                             │
│                                                                 │
│  data.processed ─────────────────────→ processed.data.queue     │
│                                      "data.processed"           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      alerts.exchange                            │
│                      (TopicExchange)                             │
│                                                                 │
│  alert.anomaly ──────────────────────→ alerts.queue             │
│                                      "alert.anomaly"            │
└─────────────────────────────────────────────────────────────────┘
```

This design enables **two critical architectural properties:**

1. **Producer-consumer decoupling.** The `sensor-simulator-service` publishes to `sensor.exchange`
   with a routing key like `sensor.temperature.living-room`. It has no idea which queues exist or
   how many consumers are running. You can add a new queue bound with `sensor.temperature.*` to
   capture only temperature readings — and the simulator requires zero code changes.

2. **Multiple routing paths from a single exchange.** The `sensor.exchange` serves double duty: it
   routes raw readings (via `sensor.#`) *and* processed data (via `data.processed`) to different
   queues. The gateway publishes both types of message to the same exchange, and the binding
   patterns ensure each message reaches only its intended queue.

A separate `alerts.exchange` exists for alert messages. While the project could route alerts through
`sensor.exchange` as well, using a dedicated exchange makes the intent explicit and allows
independent configuration (e.g., setting different policies or permissions on the alerts exchange
without affecting sensor data flow).

---

### Durable Queues

#### What Is a Durable Queue?

A queue is a **buffer** where messages wait until a consumer retrieves them. When you declare a queue
as **durable**, RabbitMQ writes the queue's metadata to disk. Combined with **persistent message
delivery** (which Spring AMQP enables by default), this means that both the queue definition *and*
the messages inside it survive a broker restart.

The alternative — a **transient** (non-durable) queue — lives only in memory. If RabbitMQ restarts,
the queue and all its messages vanish. This is acceptable for ephemeral data but catastrophic for
sensor readings that may represent safety-critical anomalies.

#### How This Project Defines Durable Queues

Three durable queues are defined in `RabbitMQConstants.java`:

```java
public static final String SENSOR_READINGS_QUEUE = "sensor.readings.queue";
public static final String PROCESSED_DATA_QUEUE  = "processed.data.queue";
public static final String ALERTS_QUEUE           = "alerts.queue";
```

Each queue is declared as a Spring `@Bean`. Again, two equivalent API styles appear across the
services:

**In `sensor-simulator-service` and `processing-service` (constructor style):**

```java
@Bean
public Queue sensorReadingsQueue() {
    return new Queue(RabbitMQConstants.SENSOR_READINGS_QUEUE, true);
    //               name: "sensor.readings.queue"             durable
}
```

The second argument `true` is the durability flag.

**In `gateway-service` (builder style):**

```java
@Bean
public Queue sensorReadingsQueue() {
    return QueueBuilder
            .durable(RabbitMQConstants.SENSOR_READINGS_QUEUE)
            .build();
}
```

`QueueBuilder.durable(...)` is a named factory method that reads more explicitly — the word
"durable" appears in the method name itself.

#### Why Each Queue Is Durable — and Why Each Queue Exists

| Queue | Constant | Data It Holds | Why Durable? |
|---|---|---|---|
| `sensor.readings.queue` | `SENSOR_READINGS_QUEUE` | Raw `SensorReading` JSON messages from the simulator | If the gateway service is temporarily down for a deployment or restart, readings accumulate here instead of being lost. When the gateway comes back, it drains the backlog. Without durability, a broker restart during this window would destroy all buffered readings. |
| `processed.data.queue` | `PROCESSED_DATA_QUEUE` | Validated and enriched `SensorDataEvent` messages from the gateway | The processing service may scale down to zero instances during low-traffic periods. Durability ensures that enriched events are not lost before a consumer instance starts back up. |
| `alerts.queue` | `ALERTS_QUEUE` | `AlertEvent` messages for anomalies (threshold breaches) | Alerts represent potentially safety-critical information — a temperature spike or a pressure drop. Losing an alert because the broker restarted is unacceptable. Durability guarantees that every alert is eventually delivered and processed. |

**A note on redundant declarations across services.** You will notice that `sensor.readings.queue`
is declared in both the `sensor-simulator-service` and the `gateway-service`. Similarly,
`processed.data.queue` and `alerts.queue` are declared in both the `gateway-service` and the
`processing-service`. This is intentional. RabbitMQ queue declarations are **idempotent** — declaring
a queue that already exists (with the same properties) is a harmless no-op. By having each service
declare the queues it interacts with, the system tolerates any startup order. Whether the simulator
starts first or the gateway starts first, the queues will exist when needed.

---

### Bindings

#### What Is a Binding?

A binding is a **rule** that links an exchange to a queue. It answers the question: "When a message
arrives at this exchange with this routing key, which queue should receive it?" Without bindings, an
exchange has no idea where to send messages — they would simply be discarded.

In the context of a topic exchange, each binding includes a **routing pattern** (using the `*` and
`#` wildcards described above). The exchange evaluates every binding pattern against the incoming
message's routing key and delivers the message to all queues whose patterns match.

#### How This Project Defines Bindings

The gateway service's `RabbitMQConfig.java` declares all three bindings because it is the only
service that interacts with every queue and exchange:

**Binding 1 — Raw sensor readings:**

```java
@Bean
public Binding sensorReadingsBinding(Queue sensorReadingsQueue,
                                      TopicExchange sensorExchange) {
    return BindingBuilder
            .bind(sensorReadingsQueue)             // sensor.readings.queue
            .to(sensorExchange)                    // sensor.exchange
            .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN);  // "sensor.#"
}
```

This binding says: "Any message published to `sensor.exchange` whose routing key starts with
`sensor.` should be delivered to `sensor.readings.queue`." Since the simulator constructs routing
keys like `sensor.temperature.living-room` and `sensor.humidity.bathroom`, every simulated reading
matches this pattern.

**Binding 2 — Processed data:**

```java
@Bean
public Binding processedDataBinding(Queue processedDataQueue,
                                     TopicExchange sensorExchange) {
    return BindingBuilder
            .bind(processedDataQueue)              // processed.data.queue
            .to(sensorExchange)                    // sensor.exchange
            .with(RabbitMQConstants.PROCESSED_ROUTING_KEY);       // "data.processed"
}
```

This binding says: "Messages published to `sensor.exchange` with the exact routing key
`data.processed` should go to `processed.data.queue`." The gateway's `publishProcessedData()`
method uses this routing key after validating and enriching each reading.

**Binding 3 — Alerts:**

```java
@Bean
public Binding alertsBinding(Queue alertsQueue,
                              TopicExchange alertsExchange) {
    return BindingBuilder
            .bind(alertsQueue)                     // alerts.queue
            .to(alertsExchange)                    // alerts.exchange
            .with(RabbitMQConstants.ALERT_ROUTING_KEY);            // "alert.anomaly"
}
```

This binding says: "Messages published to `alerts.exchange` with the routing key `alert.anomaly`
should go to `alerts.queue`." The gateway's `publishAlert()` method uses this path when an anomaly
is detected.

#### Why Bindings Are Architecturally Critical

Bindings are the **wiring diagram** of an event-driven system. Without them, exchanges and queues
are isolated components that cannot communicate. Bindings give you three specific architectural
capabilities in this project:

1. **Selective routing without producer knowledge.** The simulator publishes seven different routing
   keys (`sensor.temperature.living-room`, `sensor.humidity.bathroom`, etc.) but knows nothing about
   queues. The binding pattern `sensor.#` silently funnels all of them into `sensor.readings.queue`.
   If tomorrow you add a new binding with the pattern `sensor.temperature.*` pointing to a
   `temperature-only.queue`, the simulator would continue publishing unchanged — the new queue would
   automatically start receiving temperature readings.

2. **Exchange reuse for different message types.** The `sensor.exchange` has *two* bindings with
   non-overlapping patterns: `sensor.#` and `data.processed`. This allows the gateway to use one
   exchange for two purposes. A message with routing key `sensor.temperature.living-room` hits only
   the first binding; a message with routing key `data.processed` hits only the second. The binding
   patterns act as filters that prevent cross-contamination.

3. **Clean separation of concerns.** Alert messages go through `alerts.exchange` → `alerts.queue`
   via their own binding, completely independent of the sensor data path. This means you could
   reconfigure alert routing (e.g., add a second alert queue for a different notification channel)
   without affecting sensor data flow at all.

---

### Routing Keys

#### What Is a Routing Key?

A routing key is a **label** — a plain string — that a producer attaches to every message it
publishes. The producer chooses the routing key; the exchange uses it (together with binding
patterns) to decide where the message goes. Think of it as the "address" on an envelope: the
producer writes it, and the exchange reads it to determine delivery.

In a topic exchange, routing keys are structured as dot-delimited segments. Each segment represents
a dimension of the message's identity. The convention in this project is:

```
{category}.{type}.{location}
```

For example: `sensor.temperature.living-room`.

#### How This Project Defines and Uses Routing Keys

`RabbitMQConstants.java` defines four routing key constants:

```java
// Format string for building dynamic routing keys
public static final String SENSOR_ROUTING_KEY_FORMAT  = "sensor.%s.%s";

// Binding pattern that matches all sensor routing keys
public static final String SENSOR_ROUTING_KEY_PATTERN = "sensor.#";

// Fixed routing keys for downstream messages
public static final String PROCESSED_ROUTING_KEY      = "data.processed";
public static final String ALERT_ROUTING_KEY          = "alert.anomaly";
```

These constants serve two fundamentally different purposes:

**Dynamic routing keys (producers construct them at runtime):**

In `SensorSimulatorService.publishReading()`, the routing key is assembled from the sensor's type
and location:

```java
String routingKey = String.format(
    RabbitMQConstants.SENSOR_ROUTING_KEY_FORMAT,  // "sensor.%s.%s"
    sensor.getType().getName(),                    // e.g., "temperature"
    sensor.getLocation()                           // e.g., "living-room"
);
// Result: "sensor.temperature.living-room"

rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,             // "sensor.exchange"
    routingKey,
    reading
);
```

Each of the seven configured sensors produces a unique routing key:

| Sensor | Routing Key |
|---|---|
| `temp-sensor-001` | `sensor.temperature.living-room` |
| `temp-sensor-002` | `sensor.temperature.bedroom` |
| `humidity-sensor-001` | `sensor.humidity.living-room` |
| `humidity-sensor-002` | `sensor.humidity.bathroom` |
| `motion-sensor-001` | `sensor.motion.entrance` |
| `light-sensor-001` | `sensor.light.living-room` |
| `pressure-sensor-001` | `sensor.pressure.outdoor` |

**Static routing keys (fixed strings used for known downstream paths):**

The gateway uses two fixed routing keys when it re-publishes messages:

```java
// In SensorDataListener.publishProcessedData():
rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,
    RabbitMQConstants.PROCESSED_ROUTING_KEY,   // "data.processed"
    event
);

// In SensorDataListener.publishAlert():
rabbitTemplate.convertAndSend(
    RabbitMQConstants.ALERTS_EXCHANGE,
    RabbitMQConstants.ALERT_ROUTING_KEY,       // "alert.anomaly"
    alert
);
```

These do not need to be dynamic because the destination is always the same — processed data always
goes to `processed.data.queue`, and alerts always go to `alerts.queue`.

**Binding patterns (used by queues to express interest):**

The pattern `SENSOR_ROUTING_KEY_PATTERN` (`"sensor.#"`) is not a routing key that any producer
sends — it is a pattern that a queue uses in its binding to declare which routing keys it wants to
receive. The `#` wildcard matches all seven dynamic routing keys listed above, ensuring every sensor
reading reaches `sensor.readings.queue` regardless of type or location.

#### Why Routing Keys Matter for This Architecture

Routing keys are the mechanism that enables three services to operate independently while sharing a
message broker:

1. **The simulator does not need to know what the gateway wants.** It labels each message with
   descriptive metadata (`sensor.temperature.living-room`) and sends it to the exchange. If the
   gateway's interests change — say it only wants humidity readings — the gateway updates its
   binding pattern to `sensor.humidity.*`. The simulator is unaffected.

2. **The gateway can fan out to multiple destinations through different routing keys.** By
   publishing to `sensor.exchange` with `data.processed` and to `alerts.exchange` with
   `alert.anomaly`, a single gateway method can trigger delivery to two separate queues consumed
   by two separate listeners in the processing service.

3. **Future extensibility without code changes.** The `sensor.%s.%s` format embeds both sensor type
   and location into the routing key. If you later want a dashboard that shows only living-room
   sensors, you create a new queue bound with the pattern `sensor.*.living-room` — no producer or
   consumer code needs to change.

---

### Putting It All Together — The Component Interaction Map

Here is how all four components — topic exchanges, durable queues, bindings, and routing keys —
work together to move a single sensor reading from creation to final consumption:

```
① PRODUCER (SensorSimulatorService)
   Creates: SensorReading { sensorId: "temp-sensor-001", value: 23.4 }
   Routing key: "sensor.temperature.living-room"        ← ROUTING KEY (dynamic)
        │
        ▼
② TOPIC EXCHANGE: sensor.exchange                       ← TOPIC EXCHANGE
   Evaluates routing key against all bindings:
        │
        ├─ Binding: "sensor.#"  → sensor.readings.queue     MATCH ✓
        ├─ Binding: "data.processed" → processed.data.queue  NO MATCH ✗
        │
        ▼
③ DURABLE QUEUE: sensor.readings.queue                  ← DURABLE QUEUE
   Message persisted to disk; waits for consumer.       ← BINDING routed it here
        │
        ▼
④ CONSUMER (SensorDataListener in gateway-service)
   Validates → Detects anomaly → Enriches
        │
        ├─── Publishes SensorDataEvent ──────────────────────────────────┐
        │    Routing key: "data.processed"        ← ROUTING KEY (static)│
        │         │                                                     │
        │         ▼                                                     │
        │    TOPIC EXCHANGE: sensor.exchange       ← TOPIC EXCHANGE     │
        │    Binding: "data.processed" → MATCH ✓                        │
        │         │                                                     │
        │         ▼                                                     │
        │    DURABLE QUEUE: processed.data.queue   ← DURABLE QUEUE      │
        │         │                                                     │
        │         ▼                                                     │
        │    CONSUMER (ProcessedDataListener)                            │
        │    → AnalyticsService.updateWithEvent()                       │
        │                                                               │
        └─── Publishes AlertEvent (if anomaly) ─────────────────────────┘
             Routing key: "alert.anomaly"          ← ROUTING KEY (static)
                  │
                  ▼
             TOPIC EXCHANGE: alerts.exchange        ← TOPIC EXCHANGE
             Binding: "alert.anomaly" → MATCH ✓
                  │
                  ▼
             DURABLE QUEUE: alerts.queue            ← DURABLE QUEUE
                  │
                  ▼
             CONSUMER (AlertListener)
             → AlertHandlerService.handleAlert()
```

Each numbered step in this diagram corresponds to a specific line of Java code in this project.
By the time you finish reading the three service sections that follow, you will be able to trace
any message through this entire flow and identify the exact constant, bean definition, and method
call that governs each hop.

---

## Service 1: sensor-simulator-service

### 1.1 Purpose and Role

The simulator is the **data source** of the entire system. In a real IoT deployment, physical hardware
sensors would publish telemetry. Here, a Spring Boot service **replaces that hardware** by generating
realistic readings on a timer and publishing them to RabbitMQ.

**Responsibilities:**
- Generate `SensorReading` objects for seven pre-configured sensors every 2 seconds.
- Construct a topic-style routing key (`sensor.{type}.{location}`) for each reading.
- Publish each reading to the `sensor.exchange` RabbitMQ topic exchange.

This service is a **pure producer** — it publishes messages but never consumes any.

### 1.2 Spring Boot Fundamentals

#### The Entry Point — `SensorSimulatorApplication.java`

```java
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SensorSimulatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(SensorSimulatorApplication.class, args);
    }
}
```

This small class does a lot. Let us break down each annotation:

| Annotation | What It Does |
|---|---|
| `@SpringBootApplication` | A convenience shorthand that combines three annotations: `@Configuration` (this class can define `@Bean` methods), `@EnableAutoConfiguration` (Spring Boot automatically configures beans based on the JARs on your classpath — e.g., adding `spring-boot-starter-amqp` automatically configures a RabbitMQ `ConnectionFactory`), and `@ComponentScan` (Spring scans the current package and all sub-packages for classes annotated with `@Component`, `@Service`, `@Configuration`, etc., and registers them as beans). |
| `@EnableScheduling` | Activates Spring's task-scheduling infrastructure. Without this, the `@Scheduled` annotation on `generateAndPublishReadings()` would be silently ignored. Spring creates a background thread pool to execute scheduled methods. |
| `@ConfigurationPropertiesScan` | Tells Spring to find classes annotated with `@ConfigurationProperties` (like `SensorConfig`) and register them as beans. This is what makes the YAML-to-Java binding work. |

`SpringApplication.run(...)` bootstraps the application: it creates the Spring `ApplicationContext`,
performs component scanning, auto-configures beans, starts the embedded web server, and begins executing
any scheduled tasks.

#### Dependency Injection — Constructor Injection in `SensorSimulatorService`

```java
@Service
public class SensorSimulatorService {

    private final RabbitTemplate rabbitTemplate;
    private final SensorConfig sensorConfig;

    public SensorSimulatorService(RabbitTemplate rabbitTemplate, SensorConfig sensorConfig) {
        this.rabbitTemplate = rabbitTemplate;
        this.sensorConfig = sensorConfig;
    }
    // ...
}
```

**What is happening here?**

1. `@Service` marks this class as a Spring-managed bean. Spring creates exactly one instance (a
   singleton by default) and places it in the application context.
2. The constructor takes two parameters: `RabbitTemplate` and `SensorConfig`. Spring sees these
   parameters and **automatically injects** the matching beans from the context.
   - `RabbitTemplate` is created by `RabbitMQConfig.java` (see Section 1.3).
   - `SensorConfig` is created by Spring's `@ConfigurationProperties` binding.
3. The fields are `final`, meaning they cannot be reassigned after construction. This is a best
   practice called **constructor injection** — it makes dependencies explicit, supports immutability,
   and makes the class easy to unit-test (you can pass mock objects through the constructor).

> **Beginner tip:** When a Spring bean class has exactly one constructor, Spring automatically uses
> it for injection — you do not need `@Autowired`.

#### Configuration Binding — `SensorConfig.java` and `application.yml`

The simulator needs to know which sensors to simulate. Instead of hard-coding them, the project uses
`@ConfigurationProperties` to bind YAML configuration to a Java object.

**Java class:**

```java
@ConfigurationProperties(prefix = "simulation")
public class SensorConfig {
    private long interval = 2000;
    private List<SensorDefinition> sensors = new ArrayList<>();

    public static class SensorDefinition {
        private String id;
        private SensorType type;   // ← from iot-common
        private String location;
        // getters and setters...
    }
    // getters and setters...
}
```

**Matching YAML (`application.yml`):**

```yaml
simulation:
  interval: 2000
  sensors:
    - id: temp-sensor-001
      type: TEMPERATURE
      location: living-room
    - id: humidity-sensor-001
      type: HUMIDITY
      location: living-room
    # ... 5 more sensors
```

**How the binding works:**

1. Spring sees `@ConfigurationProperties(prefix = "simulation")` and looks for YAML keys under
   `simulation:`.
2. `simulation.interval` maps to `SensorConfig.interval` (a `long`).
3. `simulation.sensors` is a YAML list — Spring maps each entry to a `SensorDefinition` instance,
   populating `id`, `type`, and `location` fields via their setter methods.
4. The `type` field is a `SensorType` enum (from `iot-common`). Spring's built-in converter handles
   the string `"TEMPERATURE"` → `SensorType.TEMPERATURE` conversion automatically.

> **`@ConfigurationProperties` vs `@Value`:** `@ConfigurationProperties` is preferred for groups
> of related settings (like a list of sensors). `@Value("${some.property}")` is simpler and suitable
> for injecting a single property. You will see `@Value` used in the `processing-service` (Section 3).

### 1.3 RabbitMQ Implementation Details

#### Configuration — `RabbitMQConfig.java`

```java
@Configuration
public class RabbitMQConfig {

    @Bean
    public TopicExchange sensorExchange() {
        return new TopicExchange(RabbitMQConstants.SENSOR_EXCHANGE, true, false);
    }

    @Bean
    public Queue sensorReadingsQueue() {
        return new Queue(RabbitMQConstants.SENSOR_READINGS_QUEUE, true);
    }

    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue,
                                          TopicExchange sensorExchange) {
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

Let us unpack every piece:

**`@Configuration` and `@Bean`**

`@Configuration` tells Spring: "This class defines beans." Each method annotated with `@Bean` returns an
object that Spring registers in the application context. Other components can then have these beans
injected via constructor parameters.

**`ConnectionFactory` — Where Does It Come From?**

You may notice that `ConnectionFactory` appears as a method parameter but is never declared in this
class. That is **auto-configuration** at work. When Spring Boot sees `spring-boot-starter-amqp` on the
classpath, it automatically creates a `ConnectionFactory` bean using the connection settings from
`application.yml`:

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: smarthome
    password: smarthome123
```

The auto-configured `ConnectionFactory` manages TCP connections to the RabbitMQ broker, handles
reconnection on failure, and pools channels for efficiency.

**`Jackson2JsonMessageConverter` — JSON Serialization**

RabbitMQ messages are raw byte arrays. The `Jackson2JsonMessageConverter` acts as a translator:

- **Publishing:** converts a Java object (like `SensorReading`) → JSON string → byte array.
- **Consuming:** converts byte array → JSON string → Java object.

It uses the Jackson library under the hood. When sending, it also sets two AMQP message headers:
- `content_type: application/json`
- `__TypeId__: com.smarthome.common.dto.SensorReading`

The `__TypeId__` header tells the consumer which Java class to deserialize into, so
`@RabbitListener` methods can accept typed parameters like `SensorReading reading` directly.

**`RabbitTemplate` — The Publishing API**

`RabbitTemplate` is the central class for sending messages. Think of it as a high-level API that
hides the complexity of AMQP channel management. By setting `jsonMessageConverter` on it, every
call to `convertAndSend()` will automatically serialize objects to JSON.

#### Infrastructure Beans — Exchange, Queue, and Binding

Even though the simulator only *publishes* messages, it also declares the exchange, queue, and binding:

| Bean | Constant | Value | Why |
|---|---|---|---|
| `TopicExchange` | `SENSOR_EXCHANGE` | `"sensor.exchange"` | The exchange must exist before publishing, otherwise messages are silently discarded. |
| `Queue` | `SENSOR_READINGS_QUEUE` | `"sensor.readings.queue"` | Declaring the queue guarantees a consumer will find it. The `true` flag makes it **durable** (survives broker restarts). |
| `Binding` | `SENSOR_ROUTING_KEY_PATTERN` | `"sensor.#"` | Links the queue to the exchange. The `#` wildcard means "match any routing key starting with `sensor.`". |

When the application starts, Spring AMQP sends these declarations to RabbitMQ. If they already exist
with the same configuration, RabbitMQ treats the operation as a no-op — this is **idempotent**, so
multiple services can safely declare the same infrastructure.

#### Producing Messages — `convertAndSend()`

The actual publishing happens in `SensorSimulatorService.publishReading()`:

```java
String routingKey = String.format(
    RabbitMQConstants.SENSOR_ROUTING_KEY_FORMAT,  // "sensor.%s.%s"
    sensor.getType().getName(),                    // e.g., "temperature"
    sensor.getLocation()                           // e.g., "living-room"
);

rabbitTemplate.convertAndSend(
    RabbitMQConstants.SENSOR_EXCHANGE,  // "sensor.exchange"
    routingKey,                         // "sensor.temperature.living-room"
    reading                             // SensorReading object → JSON
);
```

**Why the routing key matters:**

The routing key is what makes topic exchanges powerful. Instead of sending to a queue directly, the
producer sends to an **exchange** with a descriptive label. The exchange then evaluates every binding
pattern and routes the message to matching queues:

```
Routing key: "sensor.temperature.living-room"

Binding "sensor.#"               → MATCH (# = zero or more words)
Binding "sensor.temperature.*"   → MATCH (* = exactly one word)
Binding "sensor.humidity.*"      → NO MATCH (humidity ≠ temperature)
Binding "alert.#"                → NO MATCH (different first word)
```

This decouples the producer from the consumer entirely — the simulator does not know (or care) which
queues exist. It simply labels its messages and lets the exchange handle routing.

### 1.4 Code Walkthrough — `SensorSimulatorService.java`

This is the most important class in the service. Here is a step-by-step walkthrough:

```java
@Service                                                     // ① Spring bean
public class SensorSimulatorService {

    private final RabbitTemplate rabbitTemplate;              // ② Injected by Spring
    private final SensorConfig sensorConfig;                 // ② Injected by Spring

    public SensorSimulatorService(RabbitTemplate rabbitTemplate,
                                   SensorConfig sensorConfig) {
        this.rabbitTemplate = rabbitTemplate;                 // ③ Constructor injection
        this.sensorConfig = sensorConfig;
    }

    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Map<String, Double> lastValues = new HashMap<>(); // ④ State tracking
```

- **①** `@Service` registers this class as a singleton bean.
- **②** Both dependencies come from the Spring context: `RabbitTemplate` from `RabbitMQConfig`,
  `SensorConfig` from `@ConfigurationProperties` binding.
- **③** Constructor injection — no `@Autowired` needed with a single constructor.
- **④** `lastValues` tracks the most recent value per sensor so readings change gradually (realism).

```java
    @Scheduled(fixedDelayString = "${simulation.interval:2000}")   // ⑤
    public void generateAndPublishReadings() {
        if (!running.get()) { return; }                            // ⑥
        sensorConfig.getSensors().forEach(this::publishReading);   // ⑦
    }
```

- **⑤** `@Scheduled` makes this method run repeatedly. `fixedDelayString` waits for the specified
  milliseconds **after** the previous execution finishes before starting again. The `${...}` syntax
  reads the value from `application.yml` (`simulation.interval`), with `2000` as a fallback default.
- **⑥** A running flag allows the simulation to be paused without stopping the application.
- **⑦** Iterates over all sensor definitions from `SensorConfig` and calls `publishReading()` for each.

```java
    public void publishReading(SensorConfig.SensorDefinition sensor) {
        SensorReading reading = generateReading(sensor);             // ⑧

        String routingKey = String.format(
            RabbitMQConstants.SENSOR_ROUTING_KEY_FORMAT,              // ⑨ "sensor.%s.%s"
            sensor.getType().getName(),
            sensor.getLocation()
        );

        rabbitTemplate.convertAndSend(                               // ⑩
            RabbitMQConstants.SENSOR_EXCHANGE,
            routingKey,
            reading
        );
    }
```

- **⑧** `generateReading()` builds a `SensorReading` using the Builder pattern from `iot-common`.
  It fills in a UUID for `readingId`, the current `Instant` for `timestamp`, and a realistically
  varying `value`.
- **⑨** The routing key is assembled from the shared constant `SENSOR_ROUTING_KEY_FORMAT` (`"sensor.%s.%s"`).
  `sensor.getType().getName()` returns the lowercase name from the `SensorType` enum (e.g., `"temperature"`).
- **⑩** `convertAndSend()` is the RabbitMQ publish call. It serializes `reading` to JSON, wraps it in
  an AMQP message, and sends it to `"sensor.exchange"` with the constructed routing key.

```java
    private SensorReading generateReading(SensorConfig.SensorDefinition sensor) {
        SensorType type = sensor.getType();                          // ⑪ From iot-common
        double value = generateRealisticValue(sensor.getId(), type);

        return SensorReading.builder()                               // ⑫ Builder from iot-common
            .readingId(UUID.randomUUID().toString())
            .sensorId(sensor.getId())
            .sensorType(type)
            .value(value)
            .unit(type.getUnit())
            .location(sensor.getLocation())
            .timestamp(Instant.now())
            .metadata(createMetadata())
            .build();
    }
```

- **⑪** `SensorType` is an enum from `iot-common`. Each constant carries physical metadata:
  `TEMPERATURE("temperature", "°C", -40.0, 85.0)`. This defines the name, unit, and valid range.
- **⑫** `SensorReading.builder()` is a Builder defined in `iot-common`. The builder pattern makes
  constructing objects with many fields readable and less error-prone.

### 1.5 Learning Summary

> **Takeaway 1 — Producers Don't Know About Consumers.**
> The `SensorSimulatorService` never mentions a queue name when publishing. It sends to an *exchange*
> with a *routing key*. This decoupling means you can add new consumers (new queues bound with
> different patterns) without changing a single line of producer code.

> **Takeaway 2 — `@ConfigurationProperties` Turns YAML Into Type-Safe Java.**
> Instead of scattering `@Value` annotations across your codebase, bind related configuration into a
> dedicated class. This gives you compile-time safety, IDE autocomplete, and clean separation between
> code and configuration.

> **Takeaway 3 — `@Scheduled` + RabbitMQ = Simple Event Source.**
> Combining Spring's scheduling with RabbitMQ publishing creates a lightweight event source that
> requires no external framework. The scheduler generates data; the message broker distributes it.

---

## Service 2: gateway-service

### 2.1 Purpose and Role

The gateway is the **brain** of the pipeline. It sits between the raw sensor data and the downstream
analytics, acting as a validation and routing layer.

**Responsibilities:**
1. **Consume** raw `SensorReading` messages from `sensor.readings.queue`.
2. **Validate** each reading (required fields, value ranges, timestamp sanity) via `ValidationService`.
3. **Detect anomalies** by comparing values against configurable thresholds via `AnomalyDetectionService`.
4. **Publish** an enriched `SensorDataEvent` to `processed.data.queue` (for analytics).
5. **Publish** an `AlertEvent` to `alerts.queue` (only when an anomaly is detected).

This service is both a **consumer** (from the sensor exchange) and a **producer** (to the processed
data and alerts exchanges). This dual role makes it the most architecturally interesting service.

### 2.2 Spring Boot Fundamentals

#### The Entry Point — `GatewayServiceApplication.java`

```java
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
```

Compared to the simulator, this entry point has **no** `@EnableScheduling` — the gateway does not
generate data on a timer. Instead, it reacts to incoming messages. However, it does include
`@ConfigurationPropertiesScan` because it has its own configuration class: `ThresholdConfig`.

#### Dependency Injection — Constructor Injection in `SensorDataListener`

The gateway's central component, `SensorDataListener`, is a textbook example of constructor injection
with multiple dependencies:

```java
@Component
public class SensorDataListener {

    private final ValidationService validationService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final RabbitTemplate rabbitTemplate;

    public SensorDataListener(ValidationService validationService,
                              AnomalyDetectionService anomalyDetectionService,
                              RabbitTemplate rabbitTemplate) {
        this.validationService = validationService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.rabbitTemplate = rabbitTemplate;
    }
    // ...
}
```

**What is each dependency?**

| Dependency | Annotation on Its Class | What It Provides |
|---|---|---|
| `ValidationService` | `@Service` | Validates `SensorReading` fields and value ranges |
| `AnomalyDetectionService` | `@Service` | Compares readings against `ThresholdConfig` to detect anomalies |
| `RabbitTemplate` | `@Bean` (in `RabbitMQConfig`) | Publishes messages to downstream queues |

Spring resolves all three automatically. If any bean is missing (e.g., you forgot to annotate a
class), the application fails to start with a clear error message telling you which dependency
could not be satisfied.

> **`@Component` vs `@Service`:** Functionally identical — both register a class as a Spring bean.
> `@Service` is a specialization of `@Component` that signals "this class contains business logic."
> `@Component` is typically used for infrastructure or listener classes.

#### Configuration Binding — `ThresholdConfig.java` and `application.yml`

Anomaly thresholds are externalized using `@ConfigurationProperties`:

**Java class:**

```java
@ConfigurationProperties(prefix = "gateway.thresholds")
public class ThresholdConfig {

    private ThresholdRange temperature = new ThresholdRange(15.0, 30.0);
    private ThresholdRange humidity    = new ThresholdRange(30.0, 70.0);
    private ThresholdRange light       = new ThresholdRange(0.0, 10000.0);
    private ThresholdRange pressure    = new ThresholdRange(950.0, 1050.0);

    public static class ThresholdRange {
        private double min;
        private double max;

        public boolean isWithinRange(double value) {
            return value >= min && value <= max;
        }

        public boolean exceedsMax(double value) { return value > max; }
        public boolean belowMin(double value)   { return value < min; }
    }

    public ThresholdRange getThresholdForType(String sensorType) {
        // Maps "TEMPERATURE" → this.temperature, "HUMIDITY" → this.humidity, etc.
    }
}
```

**Matching YAML:**

```yaml
gateway:
  thresholds:
    temperature:
      min: 15.0
      max: 30.0
    humidity:
      min: 30.0
      max: 70.0
    light:
      min: 0.0
      max: 10000.0
    pressure:
      min: 950.0
      max: 1050.0
```

Spring binds each nested YAML block (e.g., `gateway.thresholds.temperature.min`) to the corresponding
Java field path (`ThresholdConfig.temperature.min`). The `ThresholdRange` inner class provides helper
methods (`isWithinRange`, `exceedsMax`, `belowMin`) that encapsulate the comparison logic, keeping
`AnomalyDetectionService` clean.

### 2.3 RabbitMQ Implementation Details

#### Configuration — `RabbitMQConfig.java`

The gateway declares the **complete messaging topology** because it interacts with all three queues and
both exchanges:

```java
@Configuration
public class RabbitMQConfig {

    // ── Exchanges ──────────────────────────────────────────────
    @Bean
    public TopicExchange sensorExchange() {
        return ExchangeBuilder.topicExchange(RabbitMQConstants.SENSOR_EXCHANGE)
                .durable(true).build();
    }

    @Bean
    public TopicExchange alertsExchange() {
        return ExchangeBuilder.topicExchange(RabbitMQConstants.ALERTS_EXCHANGE)
                .durable(true).build();
    }

    // ── Queues ─────────────────────────────────────────────────
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

    // ── Bindings ───────────────────────────────────────────────
    @Bean
    public Binding sensorReadingsBinding(Queue sensorReadingsQueue,
                                          TopicExchange sensorExchange) {
        return BindingBuilder.bind(sensorReadingsQueue).to(sensorExchange)
                .with(RabbitMQConstants.SENSOR_ROUTING_KEY_PATTERN);  // "sensor.#"
    }

    @Bean
    public Binding processedDataBinding(Queue processedDataQueue,
                                         TopicExchange sensorExchange) {
        return BindingBuilder.bind(processedDataQueue).to(sensorExchange)
                .with(RabbitMQConstants.PROCESSED_ROUTING_KEY);       // "data.processed"
    }

    @Bean
    public Binding alertsBinding(Queue alertsQueue, TopicExchange alertsExchange) {
        return BindingBuilder.bind(alertsQueue).to(alertsExchange)
                .with(RabbitMQConstants.ALERT_ROUTING_KEY);           // "alert.anomaly"
    }

    // ── Serialization ──────────────────────────────────────────
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

**Why does the gateway declare everything?**

As the central routing component, the gateway needs:
- `sensor.readings.queue` to **consume** from (the incoming side).
- `processed.data.queue` and `alerts.queue` to **publish** to (the outgoing side).
- Both exchanges and all bindings to ensure the complete topology is created even if the gateway
  starts before the other services.

**Special note on `JavaTimeModule`:**

The gateway's `Jackson2JsonMessageConverter` explicitly registers `JavaTimeModule`:

```java
ObjectMapper objectMapper = new ObjectMapper();
objectMapper.registerModule(new JavaTimeModule());
return new Jackson2JsonMessageConverter(objectMapper);
```

This is necessary because `SensorReading.timestamp` and `SensorDataEvent.processedAt` are
`java.time.Instant` fields. Without `JavaTimeModule`, Jackson would fail to serialize/deserialize
`Instant` values correctly. The other services use the default `Jackson2JsonMessageConverter()`
constructor, which in Spring Boot's auto-configured environment handles this automatically — but the
gateway makes it explicit for reliability.

#### Infrastructure Beans Summary

| Bean | Constant Used | Value | Purpose |
|---|---|---|---|
| `TopicExchange` | `SENSOR_EXCHANGE` | `"sensor.exchange"` | Routes incoming sensor readings |
| `TopicExchange` | `ALERTS_EXCHANGE` | `"alerts.exchange"` | Routes alert notifications |
| `Queue` | `SENSOR_READINGS_QUEUE` | `"sensor.readings.queue"` | Incoming raw readings |
| `Queue` | `PROCESSED_DATA_QUEUE` | `"processed.data.queue"` | Outgoing validated data |
| `Queue` | `ALERTS_QUEUE` | `"alerts.queue"` | Outgoing anomaly alerts |
| `Binding` | `SENSOR_ROUTING_KEY_PATTERN` | `"sensor.#"` | sensor.exchange → sensor.readings.queue |
| `Binding` | `PROCESSED_ROUTING_KEY` | `"data.processed"` | sensor.exchange → processed.data.queue |
| `Binding` | `ALERT_ROUTING_KEY` | `"alert.anomaly"` | alerts.exchange → alerts.queue |

#### Producing Messages — `convertAndSend()` in `SensorDataListener`

The gateway publishes to two different destinations. Here are both:

**Publishing processed data:**

```java
private void publishProcessedData(SensorDataEvent event) {
    rabbitTemplate.convertAndSend(
            RabbitMQConstants.SENSOR_EXCHANGE,        // "sensor.exchange"
            RabbitMQConstants.PROCESSED_ROUTING_KEY,  // "data.processed"
            event
    );
}
```

This sends the enriched `SensorDataEvent` to `sensor.exchange` with routing key `"data.processed"`.
The binding `processedDataBinding` routes it to `processed.data.queue`.

**Publishing alerts:**

```java
rabbitTemplate.convertAndSend(
        RabbitMQConstants.ALERTS_EXCHANGE,    // "alerts.exchange"
        RabbitMQConstants.ALERT_ROUTING_KEY,  // "alert.anomaly"
        alert                                 // AlertEvent object
);
```

This sends to a *different* exchange — `alerts.exchange`. The binding `alertsBinding` routes it to
`alerts.queue`. Using a separate exchange for alerts is a design choice that keeps alert routing
independent from sensor data routing.

#### Consuming Messages — `@RabbitListener` in `SensorDataListener`

```java
@RabbitListener(queues = RabbitMQConstants.SENSOR_READINGS_QUEUE)
public void handleSensorReading(SensorReading reading) {
    // ...processing logic...
}
```

**What happens behind the scenes when the application starts:**

1. Spring AMQP sees the `@RabbitListener` annotation and creates a **message listener container**.
2. The container opens a connection to RabbitMQ (using the auto-configured `ConnectionFactory`) and
   subscribes to `"sensor.readings.queue"`.
3. When a message arrives, the container:
   a. Reads the `__TypeId__` header to determine the target class (`SensorReading`).
   b. Uses `Jackson2JsonMessageConverter` to deserialize the JSON body into a `SensorReading` object.
   c. Invokes `handleSensorReading()` with the deserialized object.
4. If the method returns normally, the message is **acknowledged** (removed from the queue).
5. If the method throws an exception, the message is **rejected** and requeued for retry.

All of this — connection management, deserialization, acknowledgment — is handled automatically by
Spring AMQP. You write a plain Java method and Spring does the rest.

### 2.4 Code Walkthrough — `SensorDataListener.java`

This is the heart of the gateway. Let us trace the full processing pipeline:

```java
@Component                                                           // ①
public class SensorDataListener {

    private static final String SERVICE_NAME = "gateway-service";    // ②

    private final ValidationService validationService;               // ③
    private final AnomalyDetectionService anomalyDetectionService;
    private final RabbitTemplate rabbitTemplate;
```

- **①** `@Component` registers this class as a Spring bean.
- **②** `SERVICE_NAME` is stamped into every `SensorDataEvent.processedBy` field for traceability.
- **③** Three dependencies injected via the constructor (shown in Section 2.2).

```java
    // Statistics counters                                           // ④
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesProcessed = new AtomicLong(0);
    private final AtomicLong validationFailures = new AtomicLong(0);
    private final AtomicLong anomaliesDetected = new AtomicLong(0);
```

- **④** `AtomicLong` counters track processing metrics in a thread-safe way. These can be exposed
  via a REST controller for monitoring.

```java
    @RabbitListener(queues = RabbitMQConstants.SENSOR_READINGS_QUEUE)  // ⑤
    public void handleSensorReading(SensorReading reading) {
        messagesReceived.incrementAndGet();
        String correlationId = UUID.randomUUID().toString();           // ⑥
```

- **⑤** Subscribes to `"sensor.readings.queue"`. Spring auto-deserializes JSON → `SensorReading`.
- **⑥** A unique `correlationId` is generated for every message. This ID is propagated to all
  downstream events, enabling end-to-end tracing across services. If you grep for a correlationId
  in the logs of all three services, you can trace a single reading's journey through the entire
  system.

```java
        // Step 1: Validate
        ValidationResult validationResult = validationService.validate(reading);  // ⑦

        // Step 2: Create enriched event
        SensorDataEvent event = createSensorDataEvent(                            // ⑧
                reading, validationResult, correlationId);

        if (!validationResult.valid()) {                                          // ⑨
            validationFailures.incrementAndGet();
            publishProcessedData(event);   // Still publish — marked as invalid
            return;
        }
```

- **⑦** `ValidationService.validate()` from `iot-common` checks required fields, value ranges
  (using `SensorType.isValidValue()`), and timestamp sanity. It returns a `ValidationResult` record.
- **⑧** The raw `SensorReading` is wrapped in a `SensorDataEvent` (from `iot-common`) with metadata:
  `processedAt`, `processedBy`, `valid`, `correlationId`.
- **⑨** Invalid readings are still published downstream but marked `valid=false`. This is a design
  decision: it allows the processing-service to track rejection rates without losing visibility.

```java
        // Step 3: Anomaly detection
        Optional<AnomalyDetails> anomalyOpt =
                anomalyDetectionService.detectAnomaly(reading);                   // ⑩

        if (anomalyOpt.isPresent()) {
            AnomalyDetails anomaly = anomalyOpt.get();
            anomaliesDetected.incrementAndGet();
            event.setAnomaly(true);
            event.setAnomalyDescription(anomaly.description());
            publishAlert(reading, anomaly, correlationId);                        // ⑪
        }

        // Step 4: Publish to processed data queue
        publishProcessedData(event);                                              // ⑫
        messagesProcessed.incrementAndGet();
    }
```

- **⑩** `AnomalyDetectionService.detectAnomaly()` compares the reading's value against the
  thresholds from `ThresholdConfig` (bound from `application.yml`). Returns `Optional.empty()` if
  the value is normal.
- **⑪** If an anomaly is found, two things happen: (a) the `SensorDataEvent` is annotated with
  anomaly details, and (b) an `AlertEvent` is published to `alerts.exchange` with routing key
  `"alert.anomaly"`. The `AlertEvent` (from `iot-common`) includes `severity`, `message`,
  `threshold`, `actualValue`, and the `correlationId` for tracing.
- **⑫** Every valid reading — whether anomalous or not — is published to `processed.data.queue`
  via `sensor.exchange` with routing key `"data.processed"`.

### 2.5 Learning Summary

> **Takeaway 1 — A Single `@RabbitListener` Method Can Trigger Multiple Downstream Publishes.**
> The gateway consumes from one queue and produces to two. This fan-out pattern is common in
> event-driven architectures: one event can trigger multiple side effects without the producer
> needing to know about them.

> **Takeaway 2 — Validate at the Boundary, Not in the Consumer.**
> The gateway validates data *before* it enters the downstream pipeline. This "fail-fast" approach
> means the processing-service can trust that all data it receives has been checked. This is a core
> microservices principle: validate at service boundaries.

> **Takeaway 3 — `@ConfigurationProperties` Enables Environment-Specific Behavior Without Code Changes.**
> Anomaly thresholds are different in a laboratory vs. a home vs. a warehouse. By externalizing
> them into `application.yml`, the same gateway binary can serve all environments — just change the
> configuration file or use environment variables.

---

## Service 3: processing-service

### 3.1 Purpose and Role

The processing service is the **terminal consumer** in the pipeline. It receives data that has already
been validated and enriched by the gateway, and turns it into actionable insights.

**Responsibilities:**
1. **Consume** `SensorDataEvent` messages from `processed.data.queue` and update real-time analytics.
2. **Consume** `AlertEvent` messages from `alerts.queue` and handle alert notifications.
3. **Maintain** in-memory per-sensor statistics (count, min, max, average) via `AnalyticsService`.
4. **Support horizontal scaling** — multiple instances can share the same queues using the competing
   consumers pattern.

This service is a **pure consumer** (with a `RabbitTemplate` available for potential future
forwarding, but not currently used for publishing).

### 3.2 Spring Boot Fundamentals

#### The Entry Point — `ProcessingServiceApplication.java`

```java
@SpringBootApplication
public class ProcessingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProcessingServiceApplication.class, args);
    }
}
```

This is the simplest entry point of all three services. There is no `@EnableScheduling` (no timers)
and no `@ConfigurationPropertiesScan` (configuration is handled via `@Value` instead of
`@ConfigurationProperties` — see below).

#### Dependency Injection — Constructor Injection in Listeners

Both listeners follow the same pattern — a single dependency injected via the constructor:

```java
@Component
public class ProcessedDataListener {
    private final AnalyticsService analyticsService;

    public ProcessedDataListener(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }
}
```

```java
@Component
public class AlertListener {
    private final AlertHandlerService alertHandlerService;

    public AlertListener(AlertHandlerService alertHandlerService) {
        this.alertHandlerService = alertHandlerService;
    }
}
```

Each listener has **one responsibility**: receive a message and delegate to a service. The listener
handles the RabbitMQ integration; the service handles the business logic. This separation makes the
business logic testable without needing a running RabbitMQ broker.

#### Configuration Binding — `@Value` in `AnalyticsService`

Unlike the other two services which use `@ConfigurationProperties`, the processing-service uses the
simpler `@Value` annotation for its configuration:

```java
@Service
public class AnalyticsService {

    @Value("${analytics.max-recent-readings:100}")
    private int maxRecentReadings;
    // ...
}
```

```java
@Service
public class AlertHandlerService {

    @Value("${analytics.max-recent-alerts:1000}")
    private int maxRecentAlerts;
    // ...
}
```

**How `@Value` works:**

- `${analytics.max-recent-readings:100}` reads the property `analytics.max-recent-readings` from
  `application.yml`.
- The `:100` after the property name is a **default value** — if the property is not defined, Spring
  uses `100`.
- Spring automatically converts the YAML string to an `int`.

**Matching YAML:**

```yaml
analytics:
  max-recent-readings: 100
  max-recent-alerts: 1000
```

> **When to use `@Value` vs `@ConfigurationProperties`:** Use `@Value` when you have one or two
> isolated properties. Use `@ConfigurationProperties` when you have a group of related settings
> (like a list of sensors or a nested threshold structure). The processing-service has just two
> simple numeric properties, making `@Value` the right choice.

### 3.3 RabbitMQ Implementation Details

#### Configuration — `RabbitMQConfig.java`

The processing-service has the simplest RabbitMQ config — it only needs the queues it consumes from:

```java
@Configuration
public class RabbitMQConfig {

    @Bean
    public Queue processedDataQueue() {
        return new Queue(RabbitMQConstants.PROCESSED_DATA_QUEUE, true);  // durable
    }

    @Bean
    public Queue alertsQueue() {
        return new Queue(RabbitMQConstants.ALERTS_QUEUE, true);          // durable
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

**Why no exchanges or bindings?**

The processing-service only *consumes* from queues — it does not need to know how messages get routed
to those queues. The exchanges and bindings are declared by the gateway-service, which owns the
routing logic. The processing-service still declares the queues themselves to handle a startup race
condition: if it starts before the gateway, the queues need to exist for the `@RabbitListener` to
subscribe to them.

**Why include `RabbitTemplate` if the service only consumes?**

The `RabbitTemplate` bean is declared for future extensibility (e.g., publishing acknowledgment
events or derived metrics). It also satisfies the `MessageConverter` wiring that `@RabbitListener`
uses for deserialization.

#### Consumer Concurrency Configuration

The processing-service configures its consumer behavior in `application.yml`:

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        concurrency: 1         # Start with 1 consumer thread per listener
        max-concurrency: 5     # Scale up to 5 threads under load
        acknowledge-mode: auto # Spring manages ack/nack automatically
        prefetch: 10           # Each consumer pre-fetches 10 messages
        retry:
          enabled: true
          initial-interval: 1000   # First retry after 1 second
          max-attempts: 3          # Try up to 3 times total
          max-interval: 10000      # Cap retry delay at 10 seconds
          multiplier: 2.0          # Double the delay each retry
```

**What each setting means:**

| Setting | Value | Effect |
|---|---|---|
| `concurrency` | `1` | Each `@RabbitListener` starts with 1 consumer thread. |
| `max-concurrency` | `5` | Spring can scale up to 5 concurrent threads per listener if the queue backs up. |
| `acknowledge-mode` | `auto` | Messages are automatically acknowledged after the listener method returns successfully. If an exception is thrown, the message is rejected and requeued. |
| `prefetch` | `10` | Each consumer pulls 10 messages at a time from the broker. This improves throughput but means one slow consumer can hold onto 10 messages while others are idle. |
| `retry` settings | — | If a listener throws an exception, Spring retries the message up to 3 times with exponential backoff (1s → 2s → 4s, capped at 10s). |

#### Consuming Messages — `@RabbitListener` in Both Listeners

**`ProcessedDataListener`:**

```java
@RabbitListener(queues = RabbitMQConstants.PROCESSED_DATA_QUEUE)
public void handleProcessedData(SensorDataEvent event) {
    try {
        analyticsService.updateWithEvent(event);
    } catch (Exception e) {
        log.error("Error processing sensor data event: correlationId={}, error={}",
                event.getCorrelationId(), e.getMessage(), e);
        throw e;  // Re-throw triggers retry
    }
}
```

**`AlertListener`:**

```java
@RabbitListener(queues = RabbitMQConstants.ALERTS_QUEUE)
public void handleAlert(AlertEvent alertEvent) {
    try {
        alertHandlerService.handleAlert(alertEvent);
    } catch (Exception e) {
        log.error("Error processing alert: alertId={}, error={}",
                alertEvent.getAlertId(), e.getMessage(), e);
        throw e;  // Re-throw triggers retry
    }
}
```

Both follow the same pattern:
1. Spring deserializes the JSON message into the typed parameter (`SensorDataEvent` or `AlertEvent` —
   both from `iot-common`).
2. Delegate to a `@Service` for business logic.
3. If processing fails, **re-throw the exception** so Spring's retry mechanism kicks in.
4. If all retries are exhausted, the message is either sent to a Dead Letter Queue (if configured)
   or dropped.

### 3.4 Code Walkthrough — `AnalyticsService.java`

This is the analytical core of the processing service:

```java
@Service                                                                // ①
public class AnalyticsService {

    @Value("${analytics.max-recent-readings:100}")                      // ②
    private int maxRecentReadings;

    private final ConcurrentHashMap<String, SensorStatistics>
            sensorStats = new ConcurrentHashMap<>();                    // ③

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<SensorReading>>
            recentReadings = new ConcurrentHashMap<>();                 // ④
```

- **①** `@Service` makes this a Spring-managed singleton.
- **②** `@Value` injects the max readings limit from `application.yml`, defaulting to 100.
- **③** `ConcurrentHashMap` stores per-sensor statistics. The key is `sensorId`, the value is a
  `SensorStatistics` object (from the `processing-service`'s own `model` package). Using
  `ConcurrentHashMap` is critical because multiple `@RabbitListener` threads may update statistics
  concurrently.
- **④** A second `ConcurrentHashMap` stores recent readings per sensor in bounded deques. This
  enables "last N readings" queries for trend analysis.

```java
    public void updateWithEvent(SensorDataEvent event) {
        SensorReading reading = event.getReading();                     // ⑤
        String sensorId = reading.getSensorId();

        sensorStats.compute(sensorId, (key, existingStats) -> {        // ⑥
            SensorStatistics stats = existingStats != null
                    ? existingStats
                    : SensorStatistics.create(sensorId,
                            reading.getSensorType(),
                            reading.getLocation());

            stats.updateWithReading(reading.getValue(),                 // ⑦
                                     reading.getTimestamp());
            return stats;
        });

        storeRecentReading(sensorId, reading);                          // ⑧
    }
```

- **⑤** Extracts the `SensorReading` from the `SensorDataEvent` wrapper. The `SensorReading` and
  `SensorDataEvent` classes are both from `iot-common`.
- **⑥** `ConcurrentHashMap.compute()` is an **atomic** operation. It either retrieves the existing
  `SensorStatistics` or creates a new one using `SensorStatistics.create()`. The lambda runs under
  the map's internal lock for the given key, preventing race conditions.
- **⑦** `SensorStatistics.updateWithReading()` performs incremental statistics:
  ```java
  // Inside SensorStatistics:
  public void updateWithReading(double value, Instant timestamp) {
      min = Math.min(min, value);
      max = Math.max(max, value);
      sum += value;
      count++;
      average = sum / count;
      lastUpdated = timestamp;
  }
  ```
  This is O(1) per update — no need to re-scan historical data.
- **⑧** `storeRecentReading()` adds the reading to a bounded deque and evicts old entries when the
  deque exceeds `maxRecentReadings`.

```java
    private void storeRecentReading(String sensorId, SensorReading reading) {
        recentReadings.computeIfAbsent(sensorId,                        // ⑨
                k -> new ConcurrentLinkedDeque<>())
                .addLast(reading);

        ConcurrentLinkedDeque<SensorReading> readings =
                recentReadings.get(sensorId);
        while (readings.size() > maxRecentReadings) {                   // ⑩
            readings.pollFirst();
        }
    }
```

- **⑨** `computeIfAbsent()` atomically creates a new deque if this is the first reading for the
  sensor. This avoids `NullPointerException` on the first call.
- **⑩** Eviction loop: if the deque exceeds the configured limit, the oldest readings are removed
  from the front. This creates a sliding window of the most recent readings.

### 3.5 Learning Summary

> **Takeaway 1 — Listeners Should Be Thin; Services Should Be Thick.**
> Both `ProcessedDataListener` and `AlertListener` are just a few lines of code: receive, delegate,
> handle errors. All business logic lives in `AnalyticsService` and `AlertHandlerService`. This
> separation means you can unit-test your analytics code without starting RabbitMQ.

> **Takeaway 2 — Thread Safety Is Essential in Message Consumers.**
> Because Spring may run multiple consumer threads (`max-concurrency: 5`), shared state like
> `sensorStats` must use thread-safe data structures (`ConcurrentHashMap`) and atomic operations
> (`compute()`). Failing to do this causes subtle, hard-to-debug race conditions.

> **Takeaway 3 — Retry Configuration Turns Transient Failures Into Recoverable Events.**
> The `retry` settings in `application.yml` mean a temporary database outage or network glitch
> doesn't lose messages. Spring automatically retries with exponential backoff, and you can add a
> Dead Letter Queue for messages that exhaust all retries.

---

## Cross-Service Annotation Reference

| Annotation | Used In | Purpose |
|---|---|---|
| `@SpringBootApplication` | All three entry points | Combines `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` |
| `@EnableScheduling` | `SensorSimulatorApplication` | Activates `@Scheduled` method execution |
| `@ConfigurationPropertiesScan` | Simulator, Gateway | Finds and registers `@ConfigurationProperties` classes |
| `@ConfigurationProperties` | `SensorConfig`, `ThresholdConfig` | Binds YAML configuration to typed Java objects |
| `@Configuration` | `RabbitMQConfig` (all three) | Declares a class that defines `@Bean` methods |
| `@Bean` | `RabbitMQConfig` (all three) | Registers a method's return value as a Spring bean |
| `@Service` | Business logic classes | Registers a class as a Spring bean (semantic: business logic) |
| `@Component` | Listener classes | Registers a class as a Spring bean (semantic: infrastructure) |
| `@Value` | `AnalyticsService`, `AlertHandlerService` | Injects a single property from `application.yml` |
| `@Scheduled` | `SensorSimulatorService` | Runs a method at fixed intervals |
| `@RabbitListener` | `SensorDataListener`, `ProcessedDataListener`, `AlertListener` | Subscribes a method to a RabbitMQ queue |
