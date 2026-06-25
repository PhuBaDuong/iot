package com.smarthome.iam.config;

import com.smarthome.iam.entity.Role;
import com.smarthome.iam.entity.User;
import com.smarthome.iam.repository.RoleRepository;
import com.smarthome.iam.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * =============================================================================
 * DataInitializer - idempotent reference-data seeding (Phase 3A)
 * =============================================================================
 * On startup, ensures the three RBAC roles exist and a bootstrap {@code admin}
 * user is present (created with a BCrypt-hashed password). Seeding the admin via
 * the {@link PasswordEncoder} avoids hardcoding a hash in SQL and guarantees the
 * encoding matches the configured encoder. Safe to run on every boot.
 * =============================================================================
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminEmail;
    private final String adminPassword;

    public DataInitializer(RoleRepository roleRepository,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${iam.bootstrap.admin-username:admin}") String adminUsername,
                           @Value("${iam.bootstrap.admin-email:admin@smarthome.local}") String adminEmail,
                           @Value("${iam.bootstrap.admin-password:admin123}") String adminPassword) {
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminEmail = adminEmail;
        this.adminPassword = adminPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Role admin = ensureRole("ADMIN", "Full system access - manage users, devices, configuration");
        ensureRole("OPERATOR", "Operational access - view data, manage devices, trigger simulator");
        ensureRole("VIEWER", "Read-only access - view dashboards, alerts, statistics");

        if (!userRepository.existsByUsername(adminUsername)) {
            User user = new User(adminUsername, adminEmail, passwordEncoder.encode(adminPassword));
            user.setRoles(Set.of(admin));
            userRepository.save(user);
            log.info("Seeded bootstrap admin user '{}'", adminUsername);
        }
    }

    private Role ensureRole(String name, String description) {
        return roleRepository.findByName(name)
                .orElseGet(() -> {
                    log.info("Seeding role '{}'", name);
                    return roleRepository.save(new Role(name, description));
                });
    }
}
