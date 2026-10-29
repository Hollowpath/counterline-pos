package com.counterline.pos.catalog;

import com.counterline.pos.TestRegister;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Head office restructures the catalogue: folders, tabs, items and option groups are renamed, moved,
 * reordered and removed, and anything with sales history is deactivated rather than deleted. Starts
 * from an empty catalogue; real dashboard tokens, test JWTs for the till.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CatalogManagementIntegrationTest {

    private static final String BASE = "/api/v1/dashboard/catalog";

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
    private Long cashierId;

    @BeforeEach
    void emptyCatalogueAnAdminAndATill() throws Exception {
        jdbc.execute("TRUNCATE item, category, quickgrid_tab, promotion, login_throttle, staff, admin_user RESTART IDENTITY CASCADE");
        adminRepository.save(new AdminUser("head@manage.test", "Head Office", passwordEncoder.encode(adminPassword), Instant.now()));
        admin = token("head@manage.test", adminPassword);
        cashierId = staffRepository.save(new Staff("Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    // ---- folders ----

    @Test
    void aFolderIsRenamedMovedAndTakenOffTheGrid() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long tin = folder("Tin", "Selling Items");

        call(put(BASE + "/folders/" + pouch), admin, "{\"name\":\"Pouches\",\"tab\":\"Selling Items\",\"countsForPromotions\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pouches")).andExpect(jsonPath("$.countsForPromotions").value(true))
                .andExpect(jsonPath("$.row").value(1)).andExpect(jsonPath("$.col").value(1));

        // To another tab (created): the first free cell there. To a chosen free cell on the same tab.
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"tab\":\"Gifts\"}")
                .andExpect(jsonPath("$.tab").value("Gifts")).andExpect(jsonPath("$.row").value(1)).andExpect(jsonPath("$.col").value(1));
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"tab\":\"gifts\",\"row\":3,\"col\":4}")
                .andExpect(jsonPath("$.row").value(3)).andExpect(jsonPath("$.col").value(4));
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[*].name").value(contains("Selling Items", "Gifts")))
                .andExpect(jsonPath("$[1].folders[0].row").value(3));

        // Off the grid: still in the library, gone from the till.
        call(put(BASE + "/folders/" + pouch), admin, "{\"name\":\"Pouches\",\"tab\":null}")
                .andExpect(jsonPath("$.tab").doesNotExist()).andExpect(jsonPath("$.row").doesNotExist());
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt())).andExpect(jsonPath("$[0].folders").value(empty()));
        call(get(BASE + "/items"), admin, null).andExpect(jsonPath("$[*].name").value(hasItem("Pouches")));
        // And back on, into the first free cell.
        call(put(BASE + "/folders/" + pouch), admin, "{\"name\":\"Pouches\",\"tab\":\"Selling Items\"}")
                .andExpect(jsonPath("$.tab").value("Selling Items")).andExpect(jsonPath("$.col").value(1));
    }

    @Test
    void folderChangesAreValidated() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long tin = folder("Tin", "Selling Items");

        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\" pouch \",\"tab\":\"Selling Items\"}")
                .andExpect(status().isConflict());
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"tab\":\"Selling Items\",\"row\":1,\"col\":1}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("is taken")));
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"tab\":\"Selling Items\",\"row\":6,\"col\":1}")
                .andExpect(status().isBadRequest());
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"tab\":\"Selling Items\",\"row\":2}")
                .andExpect(status().isBadRequest());
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"Tin\",\"row\":2,\"col\":2}")
                .andExpect(status().isBadRequest());
        call(put(BASE + "/folders/" + tin), admin, "{\"name\":\"  \",\"tab\":\"Selling Items\"}").andExpect(status().isBadRequest());
        call(put(BASE + "/folders/999"), admin, "{\"name\":\"X\",\"tab\":\"Selling Items\"}").andExpect(status().isNotFound());

        // A full destination tab refuses another folder; the folder stays where it was.
        for (int i = 1; i <= 25; i++) {
            folder("Full " + i, "Full Tab");
        }
        call(put(BASE + "/folders/" + pouch), admin, "{\"name\":\"Pouch\",\"tab\":\"Full Tab\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("tab is full")));
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt()))
                .andExpect(jsonPath("$[0].name").value("Selling Items"))
                .andExpect(jsonPath("$[0].folders[*].name").value(contains("Pouch", "Tin")));
    }

    @Test
    void onlyAnEmptyFolderCanBeDeleted() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long itemId = item(pouch, "Pouch Caramel", "14.50");

        call(delete(BASE + "/folders/" + pouch), admin, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("still has 1 item")));
        // Moving the item out empties it.
        long tin = folder("Tin", "Selling Items");
        call(put(BASE + "/items/" + itemId), admin, "{\"categoryId\":" + tin + ",\"name\":\"Pouch Caramel\",\"price\":14.50}")
                .andExpect(status().isOk());

        call(delete(BASE + "/folders/" + pouch), admin, null).andExpect(status().isNoContent());
        call(delete(BASE + "/folders/" + pouch), admin, null).andExpect(status().isNotFound());
        call(get(BASE + "/items"), admin, null).andExpect(jsonPath("$[*].name").value(contains("Tin")));
    }

    // ---- tabs ----

    @Test
    void tabsAreRenamedReorderedAndDeletedOnlyWhenEmpty() throws Exception {
        long pouch = folder("Pouch", "First");
        folder("Tin", "Second");
        call(get(BASE + "/tabs"), admin, null).andExpect(jsonPath("$[*].name").value(contains("First", "Second")))
                .andExpect(jsonPath("$[0].folders").value(1));
        Integer first = JsonPath.read(call(get(BASE + "/tabs"), admin, null).andReturn().getResponse().getContentAsString(), "$[0].id");
        Integer second = JsonPath.read(call(get(BASE + "/tabs"), admin, null).andReturn().getResponse().getContentAsString(), "$[1].id");

        call(put(BASE + "/tabs/" + first), admin, "{\"name\":\"Selling Items\"}").andExpect(jsonPath("$.name").value("Selling Items"));
        call(put(BASE + "/tabs/" + first), admin, "{\"name\":\" SECOND \"}").andExpect(status().isConflict());
        call(put(BASE + "/tabs/999"), admin, "{\"name\":\"X\"}").andExpect(status().isNotFound());

        call(put(BASE + "/tabs/order"), admin, "{\"ids\":[" + second + "," + first + "]}")
                .andExpect(jsonPath("$[*].name").value(contains("Second", "Selling Items")));
        mockMvc.perform(get("/api/v1/catalog/tabs").with(jwt())).andExpect(jsonPath("$[*].name").value(contains("Second", "Selling Items")));
        call(put(BASE + "/tabs/order"), admin, "{\"ids\":[" + second + "]}").andExpect(status().isBadRequest());
        call(put(BASE + "/tabs/order"), admin, "{\"ids\":[" + second + "," + second + "]}").andExpect(status().isBadRequest());

        call(delete(BASE + "/tabs/" + first), admin, null)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("still has 1 folder")));
        call(put(BASE + "/folders/" + pouch), admin, "{\"name\":\"Pouch\",\"tab\":\"Second\"}").andExpect(status().isOk());
        call(delete(BASE + "/tabs/" + first), admin, null).andExpect(status().isNoContent());
        call(delete(BASE + "/tabs/" + first), admin, null).andExpect(status().isNotFound());
    }

    // ---- items ----

    @Test
    void itemsMoveBetweenFoldersAndAreReordered() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long tin = folder("Tin", "Selling Items");
        long caramel = item(pouch, "Caramel", "14.50");
        long cheese = item(pouch, "Cheese", "14.50");
        long tinCaramel = item(tin, "Caramel", "29.00");

        call(put(BASE + "/items/" + cheese), admin, "{\"categoryId\":" + tin + ",\"name\":\"Caramel\",\"price\":14.50}")
                .andExpect(status().isConflict());
        call(put(BASE + "/items/" + cheese), admin, "{\"categoryId\":999,\"name\":\"Cheese\",\"price\":14.50}")
                .andExpect(status().isNotFound());
        call(put(BASE + "/items/" + cheese), admin, "{\"categoryId\":" + tin + ",\"name\":\"Cheese\",\"price\":14.50}")
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/catalog/categories/" + tin + "/items").with(jwt()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Caramel", "Cheese")));
        mockMvc.perform(get("/api/v1/catalog/categories/" + pouch + "/items").with(jwt()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Caramel")));

        call(put(BASE + "/folders/" + tin + "/items/order"), admin, "{\"ids\":[" + cheese + "," + tinCaramel + "]}")
                .andExpect(jsonPath("$[*].name").value(contains("Cheese", "Caramel")));
        mockMvc.perform(get("/api/v1/catalog/categories/" + tin + "/items").with(jwt()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Cheese", "Caramel")));
        call(put(BASE + "/folders/" + tin + "/items/order"), admin, "{\"ids\":[" + cheese + "]}").andExpect(status().isBadRequest());
        call(put(BASE + "/folders/" + tin + "/items/order"), admin, "{\"ids\":[" + cheese + "," + caramel + "]}")
                .andExpect(status().isBadRequest());
        call(put(BASE + "/folders/999/items/order"), admin, "{\"ids\":[]}").andExpect(status().isNotFound());
    }

    @Test
    void aNeverSoldItemIsDeletedAndASoldOneIsTakenOffSaleWithItsHistory() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long unsold = item(pouch, "Unsold", "10.00");
        long sold = item(pouch, "Sold", "12.00");
        long group = group(unsold, "Flavours", 1);
        option(group, "Caramel", "0.00");
        String order = sell(sold, 12.00, "[]");

        call(delete(BASE + "/items/" + unsold), admin, null).andExpect(jsonPath("$.outcome").value("DELETED"));
        call(delete(BASE + "/items/" + unsold), admin, null).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/catalog/categories/" + pouch + "/items").with(jwt()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Sold")));

        call(delete(BASE + "/items/" + sold), admin, null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));
        // Off the till, still listed on the dashboard, and the past sale is untouched.
        mockMvc.perform(get("/api/v1/catalog/categories/" + pouch + "/items").with(jwt()))
                .andExpect(jsonPath("$.items").value(empty()));
        call(get(BASE + "/items"), admin, null)
                .andExpect(jsonPath("$[0].items[?(@.name == 'Sold')].active").value(contains(false)));
        mockMvc.perform(get("/api/v1/orders/" + order).with(till()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].itemName").value("Sold"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(12.0));
        // Repeating it is harmless.
        call(delete(BASE + "/items/" + sold), admin, null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));
    }

    // ---- option groups and options ----

    @Test
    void optionGroupsAndOptionsAreManagedAndReordered() throws Exception {
        long gift = item(folder("Tin", "Selling Items"), "Gift Box", "70.00");
        long flavours = group(gift, "Flavours", 3);
        long caramel = option(flavours, "Caramel", "0.00");
        long matcha = option(flavours, "Matcha", "2.00");

        call(get(BASE + "/items/" + gift + "/modifier-groups"), admin, null)
                .andExpect(jsonPath("$[0].name").value("Flavours")).andExpect(jsonPath("$[0].chooseExactly").value(3))
                .andExpect(jsonPath("$[0].options[*].name").value(contains("Caramel", "Matcha")));
        mockMvc.perform(get("/api/v1/catalog/categories/1/items").with(jwt()))
                .andExpect(jsonPath("$.items[0].hasOptions").value(true));
        mockMvc.perform(get("/api/v1/catalog/items/" + gift + "/options").with(jwt()))
                .andExpect(jsonPath("$.groups[0].options[*].name").value(contains("Caramel", "Matcha")));

        call(put(BASE + "/modifier-groups/" + flavours), admin, "{\"name\":\"Pick\",\"chooseExactly\":2}")
                .andExpect(jsonPath("$.name").value("Pick")).andExpect(jsonPath("$.chooseExactly").value(2));
        call(put(BASE + "/modifier-options/" + matcha), admin, "{\"name\":\"Matcha\",\"price\":2.50,\"active\":false}")
                .andExpect(jsonPath("$.price").value(2.5)).andExpect(jsonPath("$.active").value(false));
        // An inactive option is hidden from the till but kept in the dashboard.
        mockMvc.perform(get("/api/v1/catalog/items/" + gift + "/options").with(jwt()))
                .andExpect(jsonPath("$.groups[0].options[*].name").value(contains("Caramel")));
        call(put(BASE + "/modifier-groups/" + flavours + "/options/order"), admin, "{\"ids\":[" + matcha + "," + caramel + "]}")
                .andExpect(jsonPath("$[*].name").value(contains("Matcha", "Caramel")));
        call(put(BASE + "/modifier-groups/" + flavours + "/options/order"), admin, "{\"ids\":[" + matcha + "]}")
                .andExpect(status().isBadRequest());

        long second = group(gift, "Extras", 1);
        call(put(BASE + "/items/" + gift + "/modifier-groups/order"), admin, "{\"ids\":[" + second + "," + flavours + "]}")
                .andExpect(jsonPath("$[*].name").value(contains("Extras", "Pick")));

        // Validation.
        call(post(BASE + "/items/" + gift + "/modifier-groups"), admin, "{\"name\":\"extras\",\"chooseExactly\":1}").andExpect(status().isConflict());
        call(post(BASE + "/items/" + gift + "/modifier-groups"), admin, "{\"name\":\"Zero\",\"chooseExactly\":0}").andExpect(status().isBadRequest());
        call(post(BASE + "/modifier-groups/" + flavours + "/options"), admin, "{\"name\":\"caramel\",\"price\":0}").andExpect(status().isConflict());
        call(post(BASE + "/modifier-groups/" + flavours + "/options"), admin, "{\"name\":\"Neg\",\"price\":-1}").andExpect(status().isBadRequest());
        call(post(BASE + "/items/999/modifier-groups"), admin, "{\"name\":\"X\",\"chooseExactly\":1}").andExpect(status().isNotFound());
        call(put(BASE + "/modifier-options/999"), admin, "{\"name\":\"X\",\"price\":0}").andExpect(status().isNotFound());
        call(get(BASE + "/items/999/modifier-groups"), admin, null).andExpect(status().isNotFound());
    }

    @Test
    void unsoldOptionsAndGroupsAreDeleted() throws Exception {
        long gift = item(folder("Tin", "Selling Items"), "Gift Box", "70.00");
        long flavours = group(gift, "Flavours", 1);
        long caramel = option(flavours, "Caramel", "0.00");

        call(delete(BASE + "/modifier-options/" + caramel), admin, null).andExpect(jsonPath("$.outcome").value("DELETED"));
        call(delete(BASE + "/modifier-options/" + caramel), admin, null).andExpect(status().isNotFound());
        option(flavours, "Cheese", "0.00");
        call(delete(BASE + "/modifier-groups/" + flavours), admin, null).andExpect(jsonPath("$.outcome").value("DELETED"));
        call(get(BASE + "/items/" + gift + "/modifier-groups"), admin, null).andExpect(jsonPath("$").value(empty()));
    }

    @Test
    void soldOptionsAreDeactivatedAndAGroupWithNoActiveOptionNoLongerBlocksASale() throws Exception {
        long gift = item(folder("Tin", "Selling Items"), "Gift Box", "70.00");
        long flavours = group(gift, "Flavours", 1);
        option(flavours, "Caramel", "0.00");
        long matcha = option(flavours, "Matcha", "2.00");
        String order = sell(gift, 72.00, "[{\"optionId\":" + matcha + ",\"quantity\":1}]");

        call(delete(BASE + "/modifier-options/" + matcha), admin, null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));
        mockMvc.perform(get("/api/v1/orders/" + order).with(till()))
                .andExpect(jsonPath("$.lines[0].modifiers[0].optionName").value("Matcha"));
        call(get(BASE + "/items/" + gift + "/modifier-groups"), admin, null)
                .andExpect(jsonPath("$[0].options[?(@.name == 'Matcha')].active").value(contains(false)));

        // Removing the sold group deactivates its options; with none active the till stops asking for a pick.
        call(delete(BASE + "/modifier-groups/" + flavours), admin, null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));
        call(get(BASE + "/items/" + gift + "/modifier-groups"), admin, null)
                .andExpect(jsonPath("$[0].options[*].active").value(not(hasItem(true))));
        mockMvc.perform(get("/api/v1/catalog/categories/1/items").with(jwt()))
                .andExpect(jsonPath("$.items[0].hasOptions").value(false));
        sell(gift, 70.00, "[]");
    }

    // ---- authorization ----

    @Test
    void onlyHeadOfficeManagesTheCatalogue() throws Exception {
        Staff manager = new Staff("Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L);
        manager.setDashboardLogin("manager@manage.test", passwordEncoder.encode(managerPassword));
        staffRepository.save(manager);
        String managerToken = token("manager@manage.test", managerPassword);
        long pouch = folder("Pouch", "Selling Items");
        long itemId = item(pouch, "Caramel", "14.50");

        String[][] requests = {
                {"PUT", "/folders/" + pouch, "{\"name\":\"X\",\"tab\":\"Selling Items\"}"},
                {"DELETE", "/folders/" + pouch, null},
                {"PUT", "/folders/" + pouch + "/items/order", "{\"ids\":[" + itemId + "]}"},
                {"GET", "/tabs", null},
                {"PUT", "/tabs/order", "{\"ids\":[]}"},
                {"PUT", "/tabs/1", "{\"name\":\"X\"}"},
                {"DELETE", "/tabs/1", null},
                {"DELETE", "/items/" + itemId, null},
                {"GET", "/items/" + itemId + "/modifier-groups", null},
                {"POST", "/items/" + itemId + "/modifier-groups", "{\"name\":\"X\",\"chooseExactly\":1}"},
                {"DELETE", "/modifier-groups/1", null},
                {"DELETE", "/modifier-options/1", null},
        };
        for (String[] request : requests) {
            call(builder(request[0], BASE + request[1]), managerToken, request[2]).andExpect(status().isForbidden());
            ResultActions anonymous = mockMvc.perform(builder(request[0], BASE + request[1])
                    .contentType(MediaType.APPLICATION_JSON).content(request[2] == null ? "" : request[2]));
            anonymous.andExpect(status().isUnauthorized());
        }
        // Nothing was changed by any of them.
        call(get(BASE + "/items"), admin, null).andExpect(jsonPath("$[0].name").value("Pouch"))
                .andExpect(jsonPath("$[0].items[0].name").value("Caramel"));
    }

    // ---- helpers ----

    private long folder(String name, String tab) throws Exception {
        return ((Number) JsonPath.read(call(post(BASE + "/folders"), admin, "{\"name\":\"" + name + "\",\"tab\":\"" + tab + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long item(long folderId, String name, String price) throws Exception {
        return ((Number) JsonPath.read(call(post(BASE + "/items"), admin,
                "{\"categoryId\":" + folderId + ",\"name\":\"" + name + "\",\"price\":" + price + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long group(long itemId, String name, int chooseExactly) throws Exception {
        return ((Number) JsonPath.read(call(post(BASE + "/items/" + itemId + "/modifier-groups"), admin,
                "{\"name\":\"" + name + "\",\"chooseExactly\":" + chooseExactly + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long option(long groupId, String name, String price) throws Exception {
        return ((Number) JsonPath.read(call(post(BASE + "/modifier-groups/" + groupId + "/options"), admin,
                "{\"name\":\"" + name + "\",\"price\":" + price + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** One of the item at the till, paid in full; returns the order id. */
    private String sell(long itemId, double total, String modifiers) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(till()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":1,"modifiers":%s}],
                         "payments":[{"paymentMethodId":2,"reference":"ref","amount":%.2f}]}""".formatted(UUID.randomUUID(), itemId, modifiers, total)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return String.valueOf((Object) JsonPath.read(body, "$.id"));
    }

    private RequestPostProcessor till() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
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

    private static MockHttpServletRequestBuilder builder(String method, String path) {
        return switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> delete(path);
        };
    }
}
