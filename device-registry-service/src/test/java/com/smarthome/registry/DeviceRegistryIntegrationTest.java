package com.smarthome.registry;

import com.smarthome.common.dto.DeviceDto;
import com.smarthome.common.dto.DeviceRegistrationRequest;
import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.common.dto.SensorType;
import com.smarthome.registry.service.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * =============================================================================
 * DeviceRegistryIntegrationTest - registry against real backing services
 * =============================================================================
 * Spins up Postgres, RabbitMQ, and Redis with Testcontainers and exercises the
 * device lifecycle (register -> heartbeat promotes to ACTIVE -> inactivity
 * sweep flips to INACTIVE) through the real {@link DeviceService} + JPA stack.
 *
 * {@code disabledWithoutDocker = true} keeps the wider build green on machines
 * without a Docker daemon.
 * =============================================================================
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DeviceRegistryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("devices");

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer(
            DockerImageName.parse("rabbitmq:3.12-management"));

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbit::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbit::getAdminPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    DeviceService deviceService;

    @Test
    void lifecycle_registerHeartbeatAndSweep() {
        DeviceRegistrationRequest request = new DeviceRegistrationRequest(
                "temp-it-001", "Test Temp", SensorType.TEMPERATURE, "lab", "1.0.0", "tester");

        DeviceDto registered = deviceService.register(request);
        assertThat(registered.status()).isEqualTo(DeviceStatus.PROVISIONED);

        // A heartbeat promotes the device to ACTIVE and records lastSeenAt.
        deviceService.recordHeartbeat("temp-it-001", Instant.now().minus(10, ChronoUnit.MINUTES));
        assertThat(deviceService.getDevice("temp-it-001").status()).isEqualTo(DeviceStatus.ACTIVE);

        // Sweeping with a recent cutoff flips the (stale) ACTIVE device to INACTIVE.
        int flipped = deviceService.sweepInactive(Instant.now().minus(5, ChronoUnit.MINUTES));
        assertThat(flipped).isEqualTo(1);
        assertThat(deviceService.getDevice("temp-it-001").status()).isEqualTo(DeviceStatus.INACTIVE);
    }

    @Test
    void decommission_marksDeviceDecommissioned() {
        deviceService.register(new DeviceRegistrationRequest(
                "light-it-001", "Test Light", SensorType.LIGHT, "lab", "1.0.0", "tester"));

        DeviceDto decommissioned = deviceService.decommission("light-it-001");
        assertThat(decommissioned.status()).isEqualTo(DeviceStatus.DECOMMISSIONED);
    }
}
