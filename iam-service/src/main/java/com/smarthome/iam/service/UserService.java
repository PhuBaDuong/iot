package com.smarthome.iam.service;

import com.smarthome.iam.dto.RegisterRequest;
import com.smarthome.iam.dto.UserResponse;
import com.smarthome.iam.entity.Role;
import com.smarthome.iam.entity.User;
import com.smarthome.iam.repository.RoleRepository;
import com.smarthome.iam.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * =============================================================================
 * UserService - user lifecycle and role assignment (Phase 3A)
 * =============================================================================
 * Backs the admin user API. Hashes passwords with BCrypt, enforces unique
 * username/email, and resolves role names to managed {@link Role} entities.
 * =============================================================================
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Username already exists: " + request.username());
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Email already exists: " + request.email());
        }
        User user = new User(
                request.username(),
                request.email(),
                passwordEncoder.encode(request.password()));
        user.setRoles(resolveRoles(request.roles()));
        return toResponse(userRepository.save(user));
    }

    @Transactional(readOnly = true)
    public List<UserResponse> listUsers() {
        return userRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public void delete(Long id) {
        if (!userRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id);
        }
        userRepository.deleteById(id);
    }

    private User findOrThrow(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + id));
    }

    private Set<Role> resolveRoles(Set<String> roleNames) {
        return roleNames.stream()
                .map(name -> roleRepository.findByName(name.toUpperCase())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "Unknown role: " + name)))
                .collect(Collectors.toSet());
    }

    private UserResponse toResponse(User user) {
        Set<String> roleNames = user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toSet());
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.isEnabled(),
                user.isAccountLocked(),
                roleNames,
                user.getCreatedAt());
    }
}
