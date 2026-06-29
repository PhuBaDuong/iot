package com.smarthome.iam.service;

import com.smarthome.iam.entity.User;
import com.smarthome.iam.repository.UserRepository;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * =============================================================================
 * CustomUserDetailsService - loads users from PostgreSQL (Phase 3A)
 * =============================================================================
 * Used by the DaoAuthenticationProvider during form login. Maps each assigned
 * {@link com.smarthome.iam.entity.Role} to a {@code ROLE_<name>} authority so the
 * token customizer can later emit the role names in the {@code roles} claim.
 * =============================================================================
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return new CustomUserDetails(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getPasswordHash(),
                user.isEnabled(),
                !user.isAccountLocked(),
                user.getRoles().stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role.getName()))
                        .toList());
    }
}
