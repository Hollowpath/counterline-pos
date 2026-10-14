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

    @Test
    void theApiStillNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/tabs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/secret.txt")).andExpect(status().isUnauthorized());
    }
}
