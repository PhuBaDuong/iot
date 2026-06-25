package com.smarthome.iam.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.Set;

/**
 * =============================================================================
 * UserResponse - safe user projection (Phase 3A)
 * =============================================================================
 * Returned by the admin user API. Never exposes the password hash.
 * =============================================================================
 */
public record UserResponse(
        Long id,
        String username,
        String email,
        boolean enabled,
        boolean accountLocked,
        Set<String> roles,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt) {
}
