package com.smarthome.iam.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * =============================================================================
 * SecurityConfig - default (non-protocol) security for the IAM service (Phase 3A)
 * =============================================================================
 * Filter chain #2 (the Authorization Server's protocol endpoints are chain #1,
 * see {@link AuthorizationServerConfig}). It handles:
 *
 *   - Form login (the page browsers are redirected to during the auth-code flow).
 *   - The admin REST API ({@code /api/users/**}) as a JWT-validating area is not
 *     needed here; admin CRUD is protected by {@code @PreAuthorize} + this chain
 *     requiring authentication. The bootstrap admin uses the login session.
 *   - Stateless-friendly defaults plus CORS for the dashboard origin.
 *
 * Also defines the {@link PasswordEncoder} (delegating: bcrypt for user
 * passwords, {noop}/{bcrypt} understood for client secrets).
 * =============================================================================
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health", "/actuator/prometheus", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                // Browser login page for the authorization-code flow.
                .formLogin(org.springframework.security.config.Customizer.withDefaults())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // The admin API is a stateless JSON API; disable CSRF for it.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
