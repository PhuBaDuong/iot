package com.smarthome.iam;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * =============================================================================
 * IamServiceIntegrationTest - end-to-end authorization-server smoke test
 * =============================================================================
 * Boots the full IAM context against a real Postgres (Testcontainers) and:
 *   1. Verifies the JWKS endpoint publishes signing keys.
 *   2. Obtains a client_credentials access token for a service client.
 *
 * {@code disabledWithoutDocker = true} keeps the wider build green without a
 * Docker daemon.
 * =============================================================================
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class IamServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("smarthome_iam");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Value("${local.server.port}")
    int port;

    private final RestClient client = RestClient.create();

    @Test
    @SuppressWarnings("unchecked")
    void jwksEndpoint_publishesKeys() {
        Map<String, Object> body = client.get()
                .uri(base() + "/oauth2/jwks")
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        assertThat(body.get("keys")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tokenEndpoint_issuesClientCredentialsToken() {
        // Default dev secret from RegisteredClientConfig (iam.clients.service-secret).
        String basic = Base64.getEncoder().encodeToString(
                "gateway-service:service-secret".getBytes(StandardCharsets.UTF_8));

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "service:internal");

        Map<String, Object> body = client.post()
                .uri(base() + "/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);

        assertThat(body).isNotNull();
        assertThat(body.get("access_token")).isNotNull();
        assertThat(body.get("token_type")).isEqualTo("Bearer");
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
