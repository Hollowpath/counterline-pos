package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Starts the whole application with bootstrap enabled on a fresh database (no {@code local}
 * profile), configured only through properties, and proves the created accounts can be used:
 * till set-up, till sign-in and both dashboard sign-ins. The credentials are generated per run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class BootstrapEnabledIntegrationTest {

    private static final String ADMIN_PASSWORD = BootstrapInitializerIntegrationTest.secret();
    private static final String MANAGER_PASSWORD = BootstrapInitializerIntegrationTest.secret();
    private static final String PIN = BootstrapInitializerIntegrationTest.randomPin();

    @DynamicPropertySource
    static void bootstrap(DynamicPropertyRegistry registry) {
        registry.add("pos.bootstrap.enabled", () -> "true");
        registry.add("pos.bootstrap.outlet-code", () -> "O1");
        registry.add("pos.bootstrap.head-office-name", () -> "Head Office");
        registry.add("pos.bootstrap.head-office-email", () -> "head@bootstrap.test");
        registry.add("pos.bootstrap.head-office-password", () -> ADMIN_PASSWORD);
        registry.add("pos.bootstrap.manager-name", () -> "Outlet Manager");
        registry.add("pos.bootstrap.manager-pin", () -> PIN);
        registry.add("pos.bootstrap.manager-email", () -> "manager@bootstrap.test");
        registry.add("pos.bootstrap.manager-password", () -> MANAGER_PASSWORD);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void theManagerSetsUpATillSignsInAndBothAccountsUseTheDashboard() throws Exception {
        String setUp = mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"" + PIN + "\",\"outletId\":1}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String deviceKey = JsonPath.read(setUp, "$.deviceKey");

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"" + PIN + "\",\"deviceKey\":\"" + deviceKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.role").value("MANAGER"))
                .andExpect(jsonPath("$.token").value(not(emptyString())));

        dashboardLogin("head@bootstrap.test", ADMIN_PASSWORD)
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("ADMIN"));
        dashboardLogin("manager@bootstrap.test", MANAGER_PASSWORD)
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("MANAGER"));
    }

    private org.springframework.test.web.servlet.ResultActions dashboardLogin(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }
}
