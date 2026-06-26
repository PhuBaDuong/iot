package com.smarthome.gateway.api.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Redis-backed rate limiting configuration for the API Gateway.
 * <p>
 * Uses the JWT {@code sub} (subject) claim as the rate-limit key so each
 * authenticated principal gets its own bucket. Unauthenticated requests
 * (which should only hit the public paths) fall back to the client IP.
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver principalKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(p -> p.getName())
                .defaultIfEmpty(
                    exchange.getRequest().getRemoteAddress() != null
                        ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                        : "anonymous"
                )
                .flatMap(Mono::just);
    }
}
