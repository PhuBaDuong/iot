package com.smarthome.iam.repository;

import com.smarthome.iam.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link User} accounts. {@code findByUsername} backs the
 * {@code CustomUserDetailsService} authentication lookup.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
