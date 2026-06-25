package com.smarthome.history.security;

import com.smarthome.history.config.SecurityConfig;
import com.smarthome.history.controller.HistoryController;
import com.smarthome.history.repository.SensorReadingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * HistorySecurityTest - resource-server authorization rules (Phase 3B)
 * =============================================================================
 * History queries are read-only and visible to any role; anonymous access is
 * rejected. Uses the spring-security-test {@code jwt()} post-processor (no
 * IAM / Docker).
 * =============================================================================
 */
@WebMvcTest(controllers = HistoryController.class)
@Import(SecurityConfig.class)
class HistorySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorReadingRepository sensorReadingRepository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void history_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/history/sensor-1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void history_withViewerRole_isOk() throws Exception {
        when(sensorReadingRepository.findBySensorIdAndTimeBetweenOrderByTimeDesc(any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/history/sensor-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void historyLatest_withViewerRole_isOk() throws Exception {
        when(sensorReadingRepository.findBySensorIdOrderByTimeDesc(any(), any()))
                .thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/history/sensor-1/latest")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }
}
