package com.smarthome.gateway.security;

import com.smarthome.common.security.JwtAuthConverterFactory;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * =============================================================================
 * JwtAuthConverterFactoryTest - shared roles claim mapping (Phase 3A)
 * =============================================================================
 * Verifies the iot-common converter maps each entry of the custom {@code roles}
 * claim to a {@code ROLE_*} authority, and yields no authorities when the claim
 * is absent.
 * =============================================================================
 */
class JwtAuthConverterFactoryTest {

    private final JwtAuthenticationConverter converter = JwtAuthConverterFactory.rolesConverter();

    @Test
    void mapsRolesClaimToRoleAuthorities() {
        Jwt jwt = baseJwt().claim("roles", List.of("ADMIN", "VIEWER")).build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        // Spring Security 7 also adds a default FACTOR_BEARER authority; we only
        // assert on the ROLE_* authorities the shared converter is responsible for.
        Set<String> roleAuthorities = roleAuthorities(auth);
        assertThat(roleAuthorities).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_VIEWER");
    }

    @Test
    void noRolesClaimYieldsNoRoleAuthorities() {
        Jwt jwt = baseJwt().build();

        AbstractAuthenticationToken auth = converter.convert(jwt);

        assertThat(roleAuthorities(auth)).isEmpty();
    }

    private Set<String> roleAuthorities(AbstractAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .collect(Collectors.toSet());
    }

    private Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }
}
