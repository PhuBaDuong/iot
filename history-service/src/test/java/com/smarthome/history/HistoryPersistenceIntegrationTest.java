package com.smarthome.history;

import com.smarthome.common.constants.RabbitMQConstants;
import com.smarthome.common.dto.SensorReading;
import com.smarthome.common.dto.SensorType;
import com.smarthome.common.event.SensorDataEvent;
import com.smarthome.history.repository.SensorReadingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

/**
 * =============================================================================
 * HistoryPersistenceIntegrationTest - end-to-end processed-data persistence
 * =============================================================================
 * Spins up TimescaleDB and RabbitMQ with Testcontainers, publishes a
 * SensorDataEvent to the processed-data routing key, and asserts the
 * history-service listener stores it in the sensor_readings hypertable.
 *
 * {@code disabledWithoutDocker = true} keeps the wider build green on machines
 * without a Docker daemon.
 * =============================================================================
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class HistoryPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> timescale = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.17.2-pg16")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("telemetry")
            .withInitScript("init-telemetry.sql");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12-management"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", timescale::getJdbcUrl);
        registry.add("spring.datasource.username", timescale::getUsername);
        registry.add("spring.datasource.password", timescale::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
    }

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    SensorReadingRepository repository;

    @Test
    void processedEvent_isPersistedToHypertable() {
        String readingId = UUID.randomUUID().toString();
        SensorReading reading = SensorReading.builder()
                .readingId(readingId)
                .sensorId("temp-hist-001")
                .sensorType(SensorType.TEMPERATURE)
                .value(21.5)
                .unit("°C")
                .location("lab")
                .timestamp(Instant.now())
                .build();
        SensorDataEvent event = SensorDataEvent.builder()
                .reading(reading)
                .processedAt(Instant.now())
                .processedBy("test")
                .valid(true)
                .anomaly(false)
                .correlationId(UUID.randomUUID().toString())
                .build();

        rabbitTemplate.convertAndSend(
                RabbitMQConstants.SENSOR_EXCHANGE,
                RabbitMQConstants.PROCESSED_ROUTING_KEY,
                event);

        await().atMost(ofSeconds(15)).untilAsserted(() ->
                assertThat(repository.findById(readingId)).isPresent());

        assertThat(repository.findById(readingId))
                .get()
                .satisfies(r -> {
                    assertThat(r.getSensorId()).isEqualTo("temp-hist-001");
                    assertThat(r.getValue()).isEqualTo(21.5);
                    assertThat(r.getSensorType()).isEqualTo("TEMPERATURE");
                });
    }
}
