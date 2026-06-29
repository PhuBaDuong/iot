package com.smarthome.sensor.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.autoconfigure.RestClientSsl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * =============================================================================
 * RestClientConfig - HTTP client for the Device Registry
 * =============================================================================
 * Used by {@link com.smarthome.sensor.service.DeviceRegistrationService} to
 * auto-register each simulated sensor with the Device Registry on startup.
 *
 * When {@code ssl.enabled=true} (Docker Compose), the "internal" SSL bundle is
 * applied so the client presents its certificate and trusts the internal CA
 * (mutual TLS — Phase 3.7).
 * =============================================================================
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient deviceRegistryRestClient(
            RestClient.Builder builder,
            @Value("${device-registry.base-url:http://localhost:8084}") String baseUrl,
            @Value("${ssl.enabled:false}") boolean sslEnabled,
            ObjectProvider<RestClientSsl> restClientSslProvider) {
        RestClient.Builder configured = builder.baseUrl(baseUrl);
        if (sslEnabled) {
            RestClientSsl ssl = restClientSslProvider.getIfAvailable();
            if (ssl != null) {
                configured.apply(ssl.fromBundle("internal"));
            }
        }
        return configured.build();
    }
}
