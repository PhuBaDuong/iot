package com.smarthome.gateway.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive security for the API Gateway.
 * <p>
 * Validates IAM-issued RS256 JWTs via the JWKS endpoint (lazy fetch).
 * Public paths: OAuth2/OIDC endpoints, actuator health/prometheus, and the
 * login-related pages. Everything else requires a valid Bearer token which is
 * then forwarded (TokenRelay) to downstream services.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        http
            .authorizeExchange(exchanges -> exchanges
                // IAM / OAuth2 endpoints — public (login, token, JWKS, discovery)
                .pathMatchers("/oauth2/**", "/login/**", "/userinfo",
                        "/.well-known/**").permitAll()
                // Actuator health + metrics — public
                .pathMatchers("/actuator/health", "/actuator/health/**",
                        "/actuator/prometheus", "/actuator/info").permitAll()
                // Everything else requires a valid JWT
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .jwtAuthenticationConverter(jwtAuthenticationConverter())
                )
            )
            .csrf(ServerHttpSecurity.CsrfSpec::disable);

        return http.build();
    }

    /**
     * Maps the custom {@code roles} claim from IAM-issued JWTs to
     * {@code ROLE_*} Spring Security authorities — same logic as the
     * servlet-based services, but adapted for the reactive stack.
     */
    private ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter grantedAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();
        grantedAuthoritiesConverter.setAuthoritiesClaimName("roles");
        grantedAuthoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(grantedAuthoritiesConverter);

        return new ReactiveJwtAuthenticationConverterAdapter(authenticationConverter);
    }
}
