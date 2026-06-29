package com.smarthome.iam.service;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

/**
 * =============================================================================
 * CustomUserDetails - authenticated principal with extra claims (Phase 3A)
 * =============================================================================
 * Extends Spring Security's {@link UserDetails} contract with the {@code id} and
 * {@code email} fields the OAuth2 token customizer copies into the access token
 * ({@code username}/{@code email}/{@code roles} claims).
 * =============================================================================
 */
public class CustomUserDetails implements UserDetails {

    private final Long id;
    private final String username;
    private final String email;
    private final String passwordHash;
    private final boolean enabled;
    private final boolean accountNonLocked;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetails(Long id, String username, String email, String passwordHash,
                             boolean enabled, boolean accountNonLocked,
                             Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.accountNonLocked = accountNonLocked;
        this.authorities = authorities;
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
