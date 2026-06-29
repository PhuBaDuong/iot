package com.smarthome.processing.security;

import com.smarthome.processing.config.SecurityConfig;
import com.smarthome.processing.controller.AnalyticsController;
import com.smarthome.processing.service.AlertHandlerService;
import com.smarthome.processing.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * ProcessingSecurityTest - resource-server authorization rules (Phase 3A)
 * =============================================================================
 * Analytics is read-only and visible to any role; anonymous access is rejected.
 * Uses the spring-security-test {@code jwt()} post-processor (no IAM / Docker).
 * =============================================================================
 */
@WebMvcTest(controllers = AnalyticsController.class)
@Import(SecurityConfig.class)
class ProcessingSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyticsService analyticsService;

    @MockitoBean
    private AlertHandlerService alertHandlerService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void summary_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/analytics/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void summary_withViewerRole_isOk() throws Exception {
        mockMvc.perform(get("/api/analytics/summary")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }
}
