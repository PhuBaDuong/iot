package com.smarthome.gateway.security;

import com.smarthome.gateway.config.SecurityConfig;
import com.smarthome.gateway.config.ThresholdConfig.ThresholdRange;
import com.smarthome.gateway.controller.GatewayController;
import com.smarthome.gateway.listener.SensorDataListener;
import com.smarthome.gateway.service.ThresholdService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * GatewaySecurityTest - resource-server authorization rules (Phase 3A)
 * =============================================================================
 * Verifies the SecurityFilterChain: anonymous requests are rejected (401),
 * read endpoints allow any role, and the admin-only threshold update is denied
 * to VIEWER (403) but allowed for ADMIN (200). Uses the spring-security-test
 * {@code jwt()} post-processor, so no IAM server or Docker is required.
 * =============================================================================
 */
@WebMvcTest(controllers = GatewayController.class)
@Import(SecurityConfig.class)
class GatewaySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorDataListener sensorDataListener;

    @MockitoBean
    private ThresholdService thresholdService;

    // Satisfies the oauth2ResourceServer().jwt() filter; never invoked because
    // the jwt() post-processor injects the authentication directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void stats_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/gateway/stats"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stats_withViewerRole_isOk() throws Exception {
        mockMvc.perform(get("/api/gateway/stats")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void updateThreshold_withViewerRole_isForbidden() throws Exception {
        mockMvc.perform(put("/api/gateway/thresholds/TEMPERATURE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min\":1.0,\"max\":2.0}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateThreshold_withAdminRole_isOk() throws Exception {
        when(thresholdService.updateThreshold(eq("TEMPERATURE"), anyDouble(), anyDouble()))
                .thenReturn(new ThresholdRange(1.0, 2.0));

        mockMvc.perform(put("/api/gateway/thresholds/TEMPERATURE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"min\":1.0,\"max\":2.0}"))
                .andExpect(status().isOk());
    }
}
