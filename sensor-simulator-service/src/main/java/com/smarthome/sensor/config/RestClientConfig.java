package com.smarthome.sensor.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * =============================================================================
 * RestClientConfig - HTTP client for the Device Registry
 * =============================================================================
 * Used by {@link com.smarthome.sensor.service.DeviceRegistrationService} to
 * auto-register each simulated sensor with the Device Registry on startup.
 * =============================================================================
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient deviceRegistryRestClient(
            RestClient.Builder builder,
            @Value("${device-registry.base-url:http://localhost:8084}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
