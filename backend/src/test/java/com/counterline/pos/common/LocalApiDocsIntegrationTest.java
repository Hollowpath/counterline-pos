package com.counterline.pos.common;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.boot.ApplicationRunner;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In the local profile the API documentation is served, openly, with a relaxed content security
 * policy of its own on its paths only; the apps and the API keep the strict one.
 */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"test", "local"})
class LocalApiDocsIntegrationTest {

    /** Not under test: it would seed the demo accounts into the shared test database. */
    @MockitoBean(name = "demoStaffSeeder")
    ApplicationRunner demoStaffSeeder;

    @Autowired
    MockMvc mockMvc;

    @Test
    void theDocumentAndSwaggerUiAreServedWithoutAToken() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"paths\"")))
                .andExpect(content().string(containsString("/api/v1/auth/special-access")));
        mockMvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    void swaggerUiHasItsOwnPolicyAndTheAppsKeepTheStrictOne() throws Exception {
        String strict = WebAppsIntegrationTest.APP_CSP;
        for (String path : new String[] {"/swagger-ui/index.html", "/swagger-ui/swagger-ui.css", "/v3/api-docs"}) {
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", not(strict)))
                    .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self';")))
                    .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
        for (String path : new String[] {"/", "/dashboard.html", "/display.html", "/api/v1/outlets", "/api/v1/catalog/tabs"}) {
            mockMvc.perform(get(path)).andExpect(header().string("Content-Security-Policy", strict));
        }
    }
}
