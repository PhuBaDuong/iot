package com.smarthome.iam.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * =============================================================================
 * RegisterRequest - admin payload to create a user (Phase 3A)
 * =============================================================================
 * Sent to {@code POST /api/users}. {@code roles} are role NAMES without the
 * {@code ROLE_} prefix (e.g. {@code ["OPERATOR"]}).
 * =============================================================================
 */
public record RegisterRequest(
        @NotBlank(message = "username is required") String username,
        @NotBlank(message = "email is required") @Email(message = "email must be valid") String email,
        @NotBlank(message = "password is required") @Size(min = 8, message = "password must be at least 8 characters") String password,
        @NotEmpty(message = "at least one role is required") Set<String> roles) {
}
