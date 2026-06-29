package com.smarthome.iam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * =============================================================================
 * IAM Service (Phase 3A)
 * =============================================================================
 * The identity provider for the platform. Built on the Spring Authorization
 * Server (merged into Spring Security 7), it:
 *
 *   1. Authenticates users (form login backed by PostgreSQL via BCrypt).
 *   2. Issues RS256-signed JWT access/refresh tokens over the standard
 *      OAuth 2.1 / OIDC protocol endpoints ({@code /oauth2/token},
 *      {@code /oauth2/authorize}, {@code /oauth2/jwks}, {@code /userinfo}).
 *   3. Enriches access tokens with custom {@code roles}/{@code username}/
 *      {@code email} claims that downstream resource servers map to authorities.
 *   4. Exposes an admin REST API to manage users and role assignments.
 * =============================================================================
 */
@SpringBootApplication
public class IamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamServiceApplication.class, args);
    }
}
