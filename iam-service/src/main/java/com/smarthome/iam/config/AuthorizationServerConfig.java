package com.smarthome.iam.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.smarthome.iam.service.CustomUserDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * =============================================================================
 * AuthorizationServerConfig - OAuth 2.1 / OIDC protocol layer (Phase 3A)
 * =============================================================================
 * Filter chain #1 (highest precedence) wires the Spring Authorization Server
 * protocol endpoints ({@code /oauth2/token}, {@code /oauth2/authorize},
 * {@code /oauth2/jwks}, {@code /userinfo}, ...). Also defines the RSA signing
 * key (JWKS), the JWT decoder, and the token customizer that adds the custom
 * {@code roles}/{@code username}/{@code email} claims resource servers rely on.
 *
 * NOTE (Spring Security 7): uses the {@code http.oauth2AuthorizationServer(...)}
 * DSL. The Boot 3 {@code OAuth2AuthorizationServerConfiguration.applyDefaultSecurity}
 * factory shown in auth_plan.md was removed. AuthorizationServerSettings is left
 * to Spring Boot auto-config so the {@code issuer} comes from application.yml.
 * =============================================================================
 */
@Configuration
public class AuthorizationServerConfig {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String ROLE_SCOPE_PREFIX = "role:";

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .oauth2AuthorizationServer(authorizationServer -> {
                    http.securityMatcher(authorizationServer.getEndpointsMatcher());
                    authorizationServer.oidc(Customizer.withDefaults());
                })
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // Browsers hitting a protocol endpoint unauthenticated are sent to the login page.
                .exceptionHandling(exceptions -> exceptions
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                // Accept JWT bearer tokens at protocol endpoints such as /userinfo.
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
        return http.build();
    }

    /**
     * Adds custom claims to access tokens:
     * - user logins (authorization_code): roles/username/email from CustomUserDetails.
     * - client_credentials: roles derived from authorized scopes prefixed "role:".
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
        return context -> {
            if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                return;
            }
            Authentication principal = context.getPrincipal();
            if (principal != null && principal.getPrincipal() instanceof CustomUserDetails user) {
                List<String> roles = user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(a -> a.startsWith(ROLE_PREFIX))
                        .map(a -> a.substring(ROLE_PREFIX.length()))
                        .toList();
                context.getClaims().claim("roles", roles);
                context.getClaims().claim("username", user.getUsername());
                context.getClaims().claim("email", user.getEmail());
            } else {
                Set<String> scopes = context.getAuthorizedScopes();
                List<String> roles = scopes.stream()
                        .filter(s -> s.startsWith(ROLE_SCOPE_PREFIX))
                        .map(s -> s.substring(ROLE_SCOPE_PREFIX.length()).toUpperCase())
                        .toList();
                if (!roles.isEmpty()) {
                    context.getClaims().claim("roles", roles);
                }
            }
        };
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generateRsaKey();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID(UUID.randomUUID().toString())
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    // NOTE: the JwtDecoder bean is contributed by Spring Boot's
    // OAuth2AuthorizationServerJwtAutoConfiguration from the JWKSource above
    // (the Spring Security 6 OAuth2AuthorizationServerConfiguration.jwtDecoder
    // helper was removed in 7.x).

    private static KeyPair generateRsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate RSA key", ex);
        }
    }
}
