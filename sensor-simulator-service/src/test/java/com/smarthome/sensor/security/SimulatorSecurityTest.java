package com.smarthome.sensor.security;

import com.smarthome.sensor.config.SecurityConfig;
import com.smarthome.sensor.config.SensorConfig;
import com.smarthome.sensor.controller.SimulatorController;
import com.smarthome.sensor.service.SensorSimulatorService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * SimulatorSecurityTest - resource-server authorization rules (Phase 3A)
 * =============================================================================
 * Status is readable by any role; simulation control (POST) requires ADMIN or
 * OPERATOR. Anonymous access is rejected. Uses the spring-security-test
 * {@code jwt()} post-processor (no IAM / Docker).
 * =============================================================================
 */
@WebMvcTest(controllers = SimulatorController.class)
@Import(SecurityConfig.class)
class SimulatorSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorSimulatorService simulatorService;

    @MockitoBean
    private SensorConfig sensorConfig;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void status_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/simulator/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void status_withViewerRole_isOk() throws Exception {
        when(sensorConfig.getSensors()).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/simulator/status")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void start_withViewerRole_isForbidden() throws Exception {
        mockMvc.perform(post("/api/simulator/start")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void start_withOperatorRole_isOk() throws Exception {
        mockMvc.perform(post("/api/simulator/start")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isOk());
    }
}
