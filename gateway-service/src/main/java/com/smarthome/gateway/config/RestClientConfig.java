package com.smarthome.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * =============================================================================
 * RestClientConfig - HTTP client for the Device Registry
 * =============================================================================
 * A {@link RestClient} pre-configured with the registry base URL. Used by
 * {@link com.smarthome.gateway.service.DeviceRegistryGateway} to validate the
 * source device of each reading.
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
