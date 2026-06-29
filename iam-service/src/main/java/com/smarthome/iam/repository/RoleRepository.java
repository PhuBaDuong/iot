package com.smarthome.iam.repository;

import com.smarthome.iam.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for RBAC {@link Role}s (ADMIN / OPERATOR / VIEWER).
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

    Optional<Role> findByName(String name);
}
