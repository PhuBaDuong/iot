package com.smarthome.iam.controller;

import com.smarthome.iam.dto.RegisterRequest;
import com.smarthome.iam.dto.UserResponse;
import com.smarthome.iam.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * =============================================================================
 * UserController - admin user management API (Phase 3A)
 * =============================================================================
 * ADMIN-only CRUD for users. Protected by method security ({@code @PreAuthorize})
 * in addition to the URL rules in {@code SecurityConfig}.
 * =============================================================================
 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return userService.register(request);
    }

    @GetMapping
    public List<UserResponse> list() {
        return userService.listUsers();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id) {
        return userService.getUser(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
