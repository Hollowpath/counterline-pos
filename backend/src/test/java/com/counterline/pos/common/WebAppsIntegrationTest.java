package com.counterline.pos.common;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The web apps are served by the API from its static files (test stand-ins under
 * src/test/resources/static): public, the pages never cached, the hashed assets cached for a year.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class WebAppsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void theAppsAreServedWithoutLoggingInAndThePagesAreNeverCached() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Counterline POS")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-cache")));
    }

    @Test
    void hashedAssetsAreCachedForAYear() throws Exception {
        mockMvc.perform(get("/assets/app-3f9a1c.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"));
    }

    /** The apps' own policy: same origin only, no inline or eval'd script, data: for images alone. */
    static final String APP_CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'none'";

    @Test
    void theAppsAndTheApiCarryTheSecurityHeaders() throws Exception {
        for (String path : List.of("/", "/index.html", "/dashboard.html", "/display.html", "/assets/app-3f9a1c.js",
                "/api/v1/outlets", "/api/v1/catalog/tabs", "/nothing-here")) {
            mockMvc.perform(get(path))
                    .andExpect(header().string("Content-Security-Policy", APP_CSP))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().doesNotExist("Strict-Transport-Security"));
        }
        // Not even a request that arrives over HTTPS (behind the load balancer later) gets HSTS yet.
        mockMvc.perform(get("/").secure(true)).andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    @Test
    void theApiDocumentationIsNotThereOutsideTheLocalProfile() throws Exception {
        // 404 for anyone, signed in or not: not a 401 or 403 that would show the paths exist.
        for (String path : List.of("/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui.html",
                "/swagger-ui/index.html", "/swagger-ui/swagger-ui.css")) {
            mockMvc.perform(get(path)).andExpect(status().isNotFound())
                    .andExpect(header().string("Content-Security-Policy", APP_CSP));
        }
    }

    @Test
    void theApiStillNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/tabs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/secret.txt")).andExpect(status().isUnauthorized());
    }
}
