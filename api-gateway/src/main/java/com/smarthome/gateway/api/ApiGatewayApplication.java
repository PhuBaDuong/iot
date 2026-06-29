package com.smarthome.gateway.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Gateway — single entry point for all external REST traffic.
 * <p>
 * Validates JWTs (IAM-issued RS256) at the edge, applies Redis-backed rate
 * limiting, logs requests, and proxies to the correct downstream service.
 * Runs on Netty (reactive stack, no Servlet container).
 */
@SpringBootApplication
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
