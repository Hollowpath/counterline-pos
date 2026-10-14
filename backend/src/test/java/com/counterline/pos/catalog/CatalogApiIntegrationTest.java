package com.counterline.pos.catalog;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the catalogue API against a real PostgreSQL (Testcontainers) with Flyway-managed schema.
 * Requests carry a test JWT; real login and token validation are covered by AuthApiIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class CatalogApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void tabsAreReturnedInDisplayOrderWithTheirFolderGrid() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Selling Items", "Promotional", "Empty Tab")))
                // Row by row, matching the visual grid.
                .andExpect(jsonPath("$[0].folders[*].name").value(contains("Pouch", "Tin", "Seasonal")))
                .andExpect(jsonPath("$[2].folders").value(empty()));
    }

    @Test
    void folderTilesCarryGridCellAndActiveItemCount() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(status().isOk())
                // Inactive items are not counted; folders with no items still appear.
                .andExpect(jsonPath("$[0].folders[?(@.name == 'Pouch')].itemCount").value(contains(2)))
                .andExpect(jsonPath("$[0].folders[?(@.name == 'Pouch')].row").value(contains(1)))
                .andExpect(jsonPath("$[0].folders[?(@.name == 'Seasonal')].itemCount").value(contains(0)))
                .andExpect(jsonPath("$[0].folders[?(@.name == 'Seasonal')].row").value(contains(5)));
    }

    @Test
    void categoryItemsAreActiveOnlyAndInDisplayOrder() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/categories/1/items").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pouch"))
                .andExpect(jsonPath("$.items[*].name").value(contains("Pouch Caramel", "Pouch Cheese")))
                .andExpect(jsonPath("$.items[0].price").value(14.5));
    }

    @Test
    void unknownCategoryReturnsProblemDetail404() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/categories/999/items").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Resource not found"))
                .andExpect(jsonPath("$.detail").value("Category 999 not found"));
    }

    @Test
    void nonNumericCategoryIdIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/categories/abc/items").with(jwt()))
                .andExpect(status().isBadRequest());
    }
}
