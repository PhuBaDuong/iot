package com.smarthome.registry.security;

import com.smarthome.common.dto.DeviceDto;
import com.smarthome.common.dto.DeviceStatus;
import com.smarthome.common.dto.SensorType;
import com.smarthome.registry.config.SecurityConfig;
import com.smarthome.registry.controller.DeviceController;
import com.smarthome.registry.service.DeviceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * =============================================================================
 * RegistrySecurityTest - resource-server authorization rules (Phase 3B)
 * =============================================================================
 * Verifies the SecurityFilterChain: anonymous requests are rejected (401),
 * read endpoints allow any role, registration requires ADMIN/OPERATOR, and
 * status update / decommission / delete are admin-only. Uses the
 * spring-security-test {@code jwt()} post-processor (no IAM / Docker).
 * =============================================================================
 */
@WebMvcTest(controllers = DeviceController.class)
@Import(SecurityConfig.class)
class RegistrySecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    // Satisfies the oauth2ResourceServer().jwt() filter; never invoked because
    // the jwt() post-processor injects the authentication directly.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static final DeviceDto SAMPLE_DEVICE = new DeviceDto(
            "sensor-1", "Temperature Sensor", SensorType.TEMPERATURE,
            "Living Room", "1.0.0", DeviceStatus.ACTIVE, "admin",
            Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-06-01T00:00:00Z"));

    @Test
    void listDevices_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listDevices_withViewerRole_isOk() throws Exception {
        when(deviceService.listDevices()).thenReturn(List.of());

        mockMvc.perform(get("/api/devices")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER"))))
                .andExpect(status().isOk());
    }

    @Test
    void register_withViewerRole_isForbidden() throws Exception {
        mockMvc.perform(post("/api/devices")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensorId\":\"s1\",\"name\":\"Sensor\",\"sensorType\":\"TEMPERATURE\",\"location\":\"Room\",\"firmwareVersion\":\"1.0\",\"owner\":\"admin\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void register_withOperatorRole_isCreated() throws Exception {
        when(deviceService.register(any())).thenReturn(SAMPLE_DEVICE);

        mockMvc.perform(post("/api/devices")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sensorId\":\"s1\",\"name\":\"Sensor\",\"sensorType\":\"TEMPERATURE\",\"location\":\"Room\",\"firmwareVersion\":\"1.0\",\"owner\":\"admin\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void updateStatus_withOperatorRole_isForbidden() throws Exception {
        mockMvc.perform(put("/api/devices/sensor-1/status")
                        .param("value", "INACTIVE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateStatus_withAdminRole_isOk() throws Exception {
        when(deviceService.updateStatus(eq("sensor-1"), eq(DeviceStatus.INACTIVE)))
                .thenReturn(SAMPLE_DEVICE);

        mockMvc.perform(put("/api/devices/sensor-1/status")
                        .param("value", "INACTIVE")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void delete_withOperatorRole_isForbidden() throws Exception {
        mockMvc.perform(delete("/api/devices/sensor-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_withAdminRole_isNoContent() throws Exception {
        doNothing().when(deviceService).delete("sensor-1");

        mockMvc.perform(delete("/api/devices/sensor-1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNoContent());
    }
}
