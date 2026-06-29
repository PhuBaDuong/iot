package com.smarthome.registry.config;

import com.smarthome.common.security.JwtAuthConverterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * =============================================================================
 * SecurityConfig - OAuth2 resource server (Phase 3B)
 * =============================================================================
 * Validates IAM-issued RS256 JWTs locally via the JWKS endpoint and enforces
 * role-based access on the device registry REST API. The {@code roles} claim is
 * mapped to {@code ROLE_*} authorities by the shared {@link JwtAuthConverterFactory}.
 *
 * Actuator health/metrics stay public for probes and Prometheus. The AMQP
 * message pipeline is unaffected (security applies to HTTP only).
 * =============================================================================
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/devices/**")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/devices")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PUT, "/api/devices/*/status")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/devices/*/decommission")
                        .hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/devices/**")
                        .hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthConverterFactory.rolesConverter())))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
