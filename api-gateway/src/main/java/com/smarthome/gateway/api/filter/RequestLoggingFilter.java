package com.smarthome.gateway.api.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that logs every request/response passing through the gateway.
 * Captures method, path, status code, and duration for operational visibility.
 */
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long start = System.currentTimeMillis();

        log.info(">>> {} {} from {}",
                request.getMethod(), request.getURI().getPath(),
                request.getRemoteAddress());

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            ServerHttpResponse response = exchange.getResponse();
            long duration = System.currentTimeMillis() - start;
            log.info("<<< {} {} → {} ({}ms)",
                    request.getMethod(), request.getURI().getPath(),
                    response.getStatusCode(), duration);
        }));
    }

    @Override
    public int getOrder() {
        // Run early so we capture the full request lifecycle
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
