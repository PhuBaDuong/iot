package com.smarthome.iam.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.time.Duration;
import java.util.UUID;

/**
 * =============================================================================
 * RegisteredClientConfig - OAuth2 clients (Phase 3A)
 * =============================================================================
 * Registered in memory (the set is static and dev-oriented; a JDBC-backed
 * repository is a Phase 4 hardening step). Defines:
 *
 *   - smarthome-dashboard : public SPA, Authorization Code + PKCE + refresh.
 *   - {gateway,processing,sensor-simulator}-service : confidential service
 *     clients using the client_credentials grant (for Phase 3B inter-service /
 *     device auth). Secrets are {noop} dev placeholders, override via env.
 * =============================================================================
 */
@Configuration
public class RegisteredClientConfig {

    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${iam.clients.service-secret:service-secret}") String serviceSecret) {

        RegisteredClient dashboard = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("smarthome-dashboard")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://localhost:5173/callback")
                .redirectUri("http://localhost:3000/callback")
                .postLogoutRedirectUri("http://localhost:5173/")
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .scope("dashboard:read")
                .scope("dashboard:write")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(15))
                        .refreshTokenTimeToLive(Duration.ofDays(7))
                        .reuseRefreshTokens(false)
                        .build())
                .build();

        RegisteredClient gateway = serviceClient("gateway-service", serviceSecret);
        RegisteredClient processing = serviceClient("processing-service", serviceSecret);
        RegisteredClient simulator = serviceClient("sensor-simulator-service", serviceSecret);

        return new InMemoryRegisteredClientRepository(dashboard, gateway, processing, simulator);
    }

    /** A confidential service client using the client_credentials grant. */
    private RegisteredClient serviceClient(String clientId, String secret) {
        return RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret("{noop}" + secret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:internal")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();
    }
}
