package com.smarthome.sensor.config;

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
 * SecurityConfig - OAuth2 resource server (Phase 3A)
 * =============================================================================
 * Validates IAM-issued RS256 JWTs locally via the JWKS endpoint. Status is
 * readable by any role; simulation control (start/stop/trigger) requires
 * ADMIN or OPERATOR. Actuator health/metrics stay public for probes/Prometheus.
 * The AMQP publishing pipeline is unaffected (security applies to HTTP only).
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
                        .requestMatchers(HttpMethod.GET, "/api/simulator/status")
                        .hasAnyRole("ADMIN", "OPERATOR", "VIEWER")
                        .requestMatchers(HttpMethod.POST, "/api/simulator/**")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtAuthConverterFactory.rolesConverter())))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
