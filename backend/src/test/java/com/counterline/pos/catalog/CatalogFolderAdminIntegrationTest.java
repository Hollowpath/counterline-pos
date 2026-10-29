package com.counterline.pos.catalog;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.AdminUser;
import com.counterline.pos.auth.AdminUserRepository;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Head office builds the till grid from nothing: the catalogue starts completely empty (no
 * fixture), folders are added to tabs, and the first item becomes sellable. Real dashboard tokens;
 * the till's catalogue reads use a test JWT as in CatalogApiIntegrationTest.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CatalogFolderAdminIntegrationTest {

    private final String adminPassword = "pw-" + UUID.randomUUID();
    private final String managerPassword = "pw-" + UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    PasswordEncoder passwordEncoder;

    private String admin;

    @BeforeEach
    void emptyCatalogueAndAnAdmin() throws Exception {
        jdbc.execute("TRUNCATE item, category, quickgrid_tab, promotion, login_throttle, staff, admin_user RESTART IDENTITY CASCADE");
        adminRepository.save(new AdminUser("head@folders.test", "Head Office", passwordEncoder.encode(adminPassword), Instant.now()));
        admin = token("head@folders.test", adminPassword);
    }

    @Test
    void anEmptyCatalogueListsNothingAndAnEmptyFolderStillShows() throws Exception {
        call(get("/api/v1/dashboard/catalog/items"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$").value(empty()));

        folder("Pouch", "Selling Items").andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pouch"))
                .andExpect(jsonPath("$.tab").value("Selling Items"))
                .andExpect(jsonPath("$.items").value(empty()));

        // The folder has no items, yet it is listed (for the dashboard) and on the till's grid.
        call(get("/api/v1/dashboard/catalog/items"), admin, null)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Pouch"))
                .andExpect(jsonPath("$[0].items").value(empty()));
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[0].name").value("Selling Items"))
                .andExpect(jsonPath("$[0].folders[0].name").value("Pouch"))
                .andExpect(jsonPath("$[0].folders[0].row").value(1))
                .andExpect(jsonPath("$[0].folders[0].col").value(1))
                .andExpect(jsonPath("$[0].folders[0].itemCount").value(0));
    }

    @Test
    void foldersReuseATabAndTakeTheFirstFreeCellRowByRow() throws Exception {
        folder("Pouch", "Selling Items").andExpect(status().isCreated());
        // The tab is found by name whatever the case, and keeps its own sort order.
        folder("Tin", " selling items ").andExpect(status().isCreated()).andExpect(jsonPath("$.tab").value("Selling Items"));
        folder("Box", "Gifts").andExpect(status().isCreated());
        folder("Bag", "Selling Items").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[*].name").value(contains("Selling Items", "Gifts")))
                .andExpect(jsonPath("$[0].folders[*].name").value(contains("Pouch", "Tin", "Bag")))
                .andExpect(jsonPath("$[0].folders[*].col").value(contains(1, 2, 3)))
                .andExpect(jsonPath("$[1].folders[0].name").value("Box"))
                .andExpect(jsonPath("$[1].folders[0].col").value(1));

        // A freed cell is the first free one again.
        jdbc.update("DELETE FROM category WHERE name = 'Tin'");
        folder("Jar", "Selling Items").andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[0].folders[?(@.name == 'Jar')].col").value(contains(2)));
    }

    @Test
    void aFolderNameCanBeUsedOnlyOnceAndMustBeValid() throws Exception {
        folder("Pouch", "Selling Items").andExpect(status().isCreated());
        folder(" pouch ", "Gifts").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("A folder called pouch already exists"));
        // Nothing was created by the refused request: no "Gifts" tab.
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt())).andExpect(jsonPath("$.length()").value(1));

        folder("  ", "Selling Items").andExpect(status().isBadRequest());
        folder("Tin", "").andExpect(status().isBadRequest());
        folder("x".repeat(81), "Selling Items").andExpect(status().isBadRequest());
        folder("Tin", "x".repeat(61)).andExpect(status().isBadRequest());
    }

    @Test
    void aFullTabRefusesAnotherFolderButOtherTabsStillWork() throws Exception {
        for (int i = 1; i <= 25; i++) {
            folder("Folder " + i, "Selling Items").andExpect(status().isCreated());
        }
        folder("One too many", "Selling Items").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("tab is full")));
        folder("One too many", "Overflow").andExpect(status().isCreated());
    }

    @Test
    void theFirstItemOfANewFolderIsSellableAtTheTill() throws Exception {
        long categoryId = ((Number) JsonPath.read(folder("Pouch", "Selling Items").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();

        call(post("/api/v1/dashboard/catalog/items"), admin,
                "{\"categoryId\":" + categoryId + ",\"name\":\"Pouch Caramel\",\"price\":14.50}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[0].folders[0].itemCount").value(1));
        mockMvc.perform(get("/api/v1/catalog/categories/" + categoryId + "/items").with(jwt()))
                .andExpect(jsonPath("$.name").value("Pouch"))
                .andExpect(jsonPath("$.items[0].name").value("Pouch Caramel"))
                .andExpect(jsonPath("$.items[0].price").value(14.5));
        call(get("/api/v1/dashboard/catalog/items"), admin, null)
                .andExpect(jsonPath("$[0].items[0].name").value("Pouch Caramel"));
    }

    @Test
    void onlyHeadOfficeCanAddFolders() throws Exception {
        Staff manager = new Staff("Folder Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L);
        manager.setDashboardLogin("manager@folders.test", passwordEncoder.encode(managerPassword));
        staffRepository.save(manager);
        String managerToken = token("manager@folders.test", managerPassword);

        call(post("/api/v1/dashboard/catalog/folders"), managerToken, "{\"name\":\"Pouch\",\"tab\":\"Selling Items\"}")
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dashboard/catalog/folders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pouch\",\"tab\":\"Selling Items\"}"))
                .andExpect(status().isUnauthorized());
        call(get("/api/v1/dashboard/catalog/items"), admin, null).andExpect(jsonPath("$").value(empty()));
    }

    private ResultActions folder(String name, String tab) throws Exception {
        return call(post("/api/v1/dashboard/catalog/folders"), admin, "{\"name\":\"" + name + "\",\"tab\":\"" + tab + "\"}");
    }

    private String token(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }
}
