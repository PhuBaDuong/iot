package com.smarthome.common.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * JwtAuthConverterFactory - Shared resource-server role mapping (Phase 3A)
 * =============================================================================
 * The IAM service (Spring Authorization Server) puts the caller's roles in a
 * custom {@code "roles"} claim (e.g. {@code ["ADMIN","OPERATOR"]}). Spring
 * Security's resource server, by default, only maps the {@code scope}/{@code scp}
 * claim to {@code SCOPE_*} authorities. This factory builds a
 * {@link JwtAuthenticationConverter} that instead maps each entry of the
 * {@code roles} claim to a {@code ROLE_*} authority so that
 * {@code @PreAuthorize("hasRole('ADMIN')")} and {@code hasRole(...)} HTTP rules
 * work consistently across every service.
 *
 * <p>Defined once here (DRY) and reused by gateway, processing, and simulator.
 * =============================================================================
 */
public final class JwtAuthConverterFactory {

    /** The custom JWT claim that carries the caller's roles. */
    public static final String ROLES_CLAIM = "roles";

    /** Authority prefix expected by Spring Security's {@code hasRole(...)}. */
    public static final String ROLE_PREFIX = "ROLE_";

    private JwtAuthConverterFactory() {
    }

    /**
     * Build a {@link JwtAuthenticationConverter} that derives {@code ROLE_*}
     * authorities from the custom {@code roles} claim. Tokens without the claim
     * yield no authorities (the request is authenticated but unauthorized for
     * role-protected endpoints).
     */
    public static JwtAuthenticationConverter rolesConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList(ROLES_CLAIM);
            if (roles == null) {
                return List.of();
            }
            return roles.stream()
                    .filter(Objects::nonNull)
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(ROLE_PREFIX + role))
                    .collect(Collectors.toList());
        });
        return converter;
    }
}
