package com.counterline.pos.catalog;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Catalogue changes made at the same time (they run one at a time, under the catalogue lock, and a leftover collision
 * is a 409), seasonal windows, "absent means unchanged", and what sold history means for moving, removing and hiding.
 * Starts from an empty catalogue.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CatalogLifecycleIntegrationTest {

    private static final String BASE = "/api/v1/dashboard/catalog";
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur"));

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    private Long cashierId;

    @BeforeEach
    void emptyCatalogueAndATill() {
        jdbc.execute("TRUNCATE item, category, quickgrid_tab, promotion, login_throttle, staff RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Catalogue Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    // ---- At the same time ----

    @Test
    void itemsGroupsAndOptionsCreatedAtOnceInOneParentAllLandInOrder() throws Exception {
        long folder = folder("Pouch", "Selling Items");
        List<MockHttpServletResponse> items = race(8, i -> call(post(BASE + "/items"),
                "{\"categoryId\":" + folder + ",\"name\":\"Flavour " + i + "\",\"price\":10.00}").andReturn().getResponse());
        assertThat(items).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
        assertThat(jdbc.queryForList("SELECT sort_order FROM item WHERE category_id = ? ORDER BY sort_order", Integer.class, folder))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);

        long item = idOf(items.getFirst());
        List<MockHttpServletResponse> groups = race(6, i -> call(post(BASE + "/items/" + item + "/modifier-groups"),
                "{\"name\":\"Group " + i + "\",\"chooseExactly\":1}").andReturn().getResponse());
        assertThat(groups).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT sort_order) FROM modifier_group WHERE item_id = ?", Long.class, item)).isEqualTo(6L);

        long group = idOf(groups.getFirst());
        List<MockHttpServletResponse> options = race(6, i -> call(post(BASE + "/modifier-groups/" + group + "/options"),
                "{\"name\":\"Option " + i + "\",\"price\":0}").andReturn().getResponse());
        assertThat(options).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT sort_order) FROM modifier_option WHERE group_id = ?", Long.class, group)).isEqualTo(6L);
    }

    @Test
    void folderNamedForTheSameNewTabAtOnceMakeOneTabAndTakeDifferentCells() throws Exception {
        List<MockHttpServletResponse> folders = race(5, i -> call(post(BASE + "/folders"),
                "{\"name\":\"Folder " + i + "\",\"tab\":\"Fresh Tab\"}").andReturn().getResponse());

        assertThat(folders).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickgrid_tab WHERE lower(name) = 'fresh tab'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT (grid_row, grid_col)) FROM category", Long.class)).isEqualTo(5L);
        // Same name, different case, at once: still one tab.
        race(3, i -> call(post(BASE + "/folders"), "{\"name\":\"Other " + i + "\",\"tab\":\"" + (i % 2 == 0 ? "NEW" : "new") + "\"}")
                .andReturn().getResponse()).forEach(answer -> assertThat(answer.getStatus()).isEqualTo(201));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM quickgrid_tab WHERE lower(name) = 'new'", Long.class)).isEqualTo(1L);
    }

    @Test
    void aReorderAndACreateAtOnceNeverFailWithAServerErrorOrLeaveGaps() throws Exception {
        for (int round = 0; round < 3; round++) {
            long folder = folder("Reorder " + round, "Selling Items");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ids.add(item(folder, "Item " + i, "10.00"));
            }
            String reversed = "{\"ids\":[" + ids.get(2) + "," + ids.get(1) + "," + ids.get(0) + "]}";

            List<MockHttpServletResponse> answers = raceAll(
                    () -> call(put(BASE + "/folders/" + folder + "/items/order"), reversed).andReturn().getResponse(),
                    () -> call(post(BASE + "/items"), "{\"categoryId\":" + folder + ",\"name\":\"Newcomer\",\"price\":10.00}").andReturn().getResponse());

            // The create always lands; the reorder either ran first (200) or was told the folder has changed (400).
            assertThat(answers.get(1).getStatus()).isEqualTo(201);
            assertThat(answers.get(0).getStatus()).isIn(200, 400);
            assertThat(jdbc.queryForList("SELECT sort_order FROM item WHERE category_id = ? ORDER BY sort_order", Integer.class, folder))
                    .containsExactly(1, 2, 3, 4);
        }
    }

    @Test
    void aReorderAndADeleteAtOnceNeverFailWithAServerError() throws Exception {
        for (int round = 0; round < 3; round++) {
            long folder = folder("Delete " + round, "Selling Items");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                ids.add(item(folder, "Item " + i, "10.00"));
            }
            String all = "{\"ids\":[" + ids.get(2) + "," + ids.get(1) + "," + ids.get(0) + "]}";

            List<MockHttpServletResponse> answers = raceAll(
                    () -> call(put(BASE + "/folders/" + folder + "/items/order"), all).andReturn().getResponse(),
                    () -> call(delete(BASE + "/items/" + ids.get(1)), null).andReturn().getResponse());

            assertThat(answers.get(1).getStatus()).isEqualTo(200);
            assertThat(answers.get(0).getStatus()).isIn(200, 400);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE category_id = ?", Long.class, folder)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT sort_order) FROM item WHERE category_id = ?", Long.class, folder)).isEqualTo(2L);
        }
    }

    @Test
    void twoFoldersMovedIntoOneGridCellAtOnceLetOneInAndTheOtherGetsAConflict() throws Exception {
        long first = folder("First", "Grid");
        long second = folder("Second", "Grid");

        List<MockHttpServletResponse> answers = raceAll(
                () -> call(put(BASE + "/folders/" + first), "{\"name\":\"First\",\"tab\":\"Grid\",\"row\":3,\"col\":3}").andReturn().getResponse(),
                () -> call(put(BASE + "/folders/" + second), "{\"name\":\"Second\",\"tab\":\"Grid\",\"row\":3,\"col\":3}").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(answers.stream().filter(answer -> answer.getStatus() == 409).findFirst().orElseThrow().getContentAsString())
                .contains("is taken");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category WHERE grid_row = 3 AND grid_col = 3", Long.class)).isEqualTo(1L);
    }

    @Test
    void deletingAnItemAtTheMomentItIsSoldEndsConsistentlyWithNoServerError() throws Exception {
        long folder = folder("Race", "Selling Items");
        for (int round = 0; round < 5; round++) {
            long item = item(folder, "Racing " + round, "10.00");
            String sale = saleBody(item);

            List<MockHttpServletResponse> answers = raceAll(
                    () -> call(delete(BASE + "/items/" + item), null).andReturn().getResponse(),
                    () -> mockMvc.perform(post("/api/v1/orders").with(till()).contentType(MediaType.APPLICATION_JSON).content(sale))
                            .andReturn().getResponse());

            MockHttpServletResponse removal = answers.get(0);
            int sold = answers.get(1).getStatus();
            assertThat(removal.getStatus()).isEqualTo(200);
            assertThat(sold).isIn(201, 409, 422);
            boolean exists = jdbc.queryForObject("SELECT count(*) FROM item WHERE id = ?", Long.class, item) == 1L;
            if (sold == 201) {
                // The sale got in first: the item is kept, off sale, with its history.
                assertThat((String) JsonPath.read(removal.getContentAsString(), "$.outcome")).isEqualTo("DEACTIVATED");
                assertThat(exists).isTrue();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order_line WHERE item_id = ?", Long.class, item)).isEqualTo(1L);
            } else {
                // The removal got in first: the item is gone and the sale was told so, saving nothing.
                assertThat((String) JsonPath.read(removal.getContentAsString(), "$.outcome")).isEqualTo("DELETED");
                assertThat(exists).isFalse();
                assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order_line", Long.class)).isEqualTo(
                        jdbc.queryForObject("SELECT count(*) FROM sale_order_line WHERE item_id IS NOT NULL AND item_id <> ?", Long.class, item));
            }
        }
    }

    @Test
    void onlyAUniqueOrForeignKeyCollisionIsAConflictAnyOtherIntegrityViolationStaysAnError() {
        CatalogErrorHandler handler = new CatalogErrorHandler();
        for (String state : List.of("23505", "23503")) {
            var problem = handler.handleCollision(new DataIntegrityViolationException("collision", new java.sql.SQLException("x", state)));
            assertThat(problem.getStatus()).isEqualTo(409);
            assertThat(problem.getDetail()).contains("collided").contains("try again");
        }
        // A check or not-null violation, or one with no known cause, is a defect: not dressed up as a conflict.
        for (Throwable defect : List.of(
                new DataIntegrityViolationException("check", new java.sql.SQLException("x", "23514")),
                new DataIntegrityViolationException("not null", new java.sql.SQLException("x", "23502")),
                new DataIntegrityViolationException("unknown"))) {
            assertThat(handler.handleCollision((DataIntegrityViolationException) defect).getStatus()).isEqualTo(500);
        }
        // The cause may be wrapped, as Hibernate does.
        assertThat(handler.handleCollision(new DataIntegrityViolationException("wrapped",
                new RuntimeException(new java.sql.SQLException("x", "23505")))).getStatus()).isEqualTo(409);
    }

    // ---- Absent means unchanged ----

    @Test
    void anUpdateWithoutActiveLeavesAnItemOrOptionAsItWasAndOnlyACreationDefaultsToActive() throws Exception {
        long folder = folder("Pouch", "Selling Items");
        long item = item(folder, "Retired Soon", "10.00");
        sell(item);
        call(delete(BASE + "/items/" + item), null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));

        // A price change that says nothing about active does not put it back on sale.
        call(put(BASE + "/items/" + item), "{\"name\":\"Retired Soon\",\"price\":11.00}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.price").value(11.0));
        mockMvc.perform(get("/api/v1/catalog/categories/" + folder + "/items").with(till())).andExpect(jsonPath("$.items").value(empty()));
        call(put(BASE + "/items/" + item), "{\"name\":\"Retired Soon\",\"price\":11.00,\"active\":true}")
                .andExpect(jsonPath("$.active").value(true));
        // And an item that is on sale stays on sale, or is taken off by saying so.
        call(put(BASE + "/items/" + item), "{\"name\":\"Retired Soon\",\"price\":12.00}").andExpect(jsonPath("$.active").value(true));
        call(put(BASE + "/items/" + item), "{\"name\":\"Retired Soon\",\"price\":12.00,\"active\":false}").andExpect(jsonPath("$.active").value(false));

        long gift = item(folder, "Gift Box", "70.00");
        long group = group(gift, "Flavours", 1);
        long option = ((Number) JsonPath.read(call(post(BASE + "/modifier-groups/" + group + "/options"), "{\"name\":\"Matcha\",\"price\":2.00,\"active\":false}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(false)).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        call(put(BASE + "/modifier-options/" + option), "{\"name\":\"Matcha\",\"price\":2.50}")
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.price").value(2.5));
        call(put(BASE + "/modifier-options/" + option), "{\"name\":\"Matcha\",\"price\":2.50,\"active\":true}").andExpect(jsonPath("$.active").value(true));
        call(put(BASE + "/modifier-options/" + option), "{\"name\":\"Matcha\",\"price\":3.00}").andExpect(jsonPath("$.active").value(true));
        // A created item and option are on sale unless the option says otherwise.
        call(get(BASE + "/items"), null).andExpect(jsonPath("$[0].items[?(@.name == 'Gift Box')].active").value(contains(true)));
    }

    // ---- Seasonal windows ----

    @Test
    void aSeasonalWindowIncludesItsFirstAndLastDayAndNeedsAnEndAndAnOrder() throws Exception {
        long folder = folder("Seasonal", "Selling Items");

        // Today is inside a window that starts or ends today.
        assertThat(seasonalNow(folder, "Starts Today", TODAY, TODAY.plusDays(5))).isTrue();
        assertThat(seasonalNow(folder, "Ends Today", TODAY.minusDays(5), TODAY)).isTrue();
        assertThat(seasonalNow(folder, "One Day", TODAY, TODAY)).isTrue();
        // Not before the first day, nor after the last.
        assertThat(seasonalNow(folder, "Tomorrow", TODAY.plusDays(1), TODAY.plusDays(9))).isFalse();
        assertThat(seasonalNow(folder, "Yesterday", TODAY.minusDays(9), TODAY.minusDays(1))).isFalse();
        // No first day means from now.
        assertThat(seasonalNow(folder, "From Now", null, TODAY.plusDays(2))).isTrue();

        call(post(BASE + "/items"), "{\"categoryId\":" + folder + ",\"name\":\"No End\",\"price\":9,\"seasonal\":true}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("last day")));
        call(post(BASE + "/items"), "{\"categoryId\":" + folder + ",\"name\":\"Reversed\",\"price\":9,\"seasonal\":true,\"seasonalFrom\":\""
                + TODAY.plusDays(3) + "\",\"seasonalUntil\":\"" + TODAY + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString("first day is after its last day")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE name IN ('No End', 'Reversed')", Long.class)).isZero();
    }

    @Test
    void anItemIsSaleableAtTheTillWhateverItsSeasonalWindowSays() throws Exception {
        long folder = folder("Seasonal", "Selling Items");
        long before = seasonalItem(folder, "Not Yet", TODAY.plusDays(10), TODAY.plusDays(20));
        long during = seasonalItem(folder, "Now", TODAY.minusDays(1), TODAY.plusDays(1));
        long after = seasonalItem(folder, "Over", TODAY.minusDays(20), TODAY.minusDays(10));

        // The window only decides the seasonal-flavour promotion: all three are listed and sell at their price.
        mockMvc.perform(get("/api/v1/catalog/categories/" + folder + "/items").with(till()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Not Yet", "Now", "Over")));
        for (long item : List.of(before, during, after)) {
            sell(item);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order WHERE status = 'CLOSED'", Long.class)).isEqualTo(3L);
        // Seasonal off keeps it just as saleable.
        call(put(BASE + "/items/" + during), "{\"name\":\"Now\",\"price\":10.00,\"seasonal\":false}").andExpect(jsonPath("$.seasonal").value(false));
        sell(during);
    }

    // ---- Sold history and what the till and the dashboard show ----

    @Test
    void aSoldItemMovedToAnotherFolderKeepsItsHistoryUnderTheFolderItWasSoldIn() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long tin = folder("Tin", "Selling Items");
        long item = item(pouch, "Mover", "10.00");
        String order = sell(item);

        call(put(BASE + "/items/" + item), "{\"categoryId\":" + tin + ",\"name\":\"Mover\",\"price\":10.00}")
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/catalog/categories/" + tin + "/items").with(till())).andExpect(jsonPath("$.items[*].name").value(contains("Mover")));
        mockMvc.perform(get("/api/v1/catalog/categories/" + pouch + "/items").with(till())).andExpect(jsonPath("$.items").value(empty()));
        assertThat(jdbc.queryForObject("SELECT category_name FROM sale_order_line WHERE order_id = ?::bigint", String.class, order)).isEqualTo("Pouch");
        mockMvc.perform(get("/api/v1/orders/" + order).with(till())).andExpect(jsonPath("$.lines[0].itemName").value("Mover"));
        // Selling it again is under its new folder.
        String second = sell(item);
        assertThat(jdbc.queryForObject("SELECT category_name FROM sale_order_line WHERE order_id = ?::bigint", String.class, second)).isEqualTo("Tin");
    }

    @Test
    void aFolderHoldingOnlyDeactivatedItemsCannotBeDeletedUntilTheyAreMovedAway() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long tin = folder("Tin", "Selling Items");
        long item = item(pouch, "Sold Once", "10.00");
        sell(item);
        call(delete(BASE + "/items/" + item), null).andExpect(jsonPath("$.outcome").value("DEACTIVATED"));

        // Nothing on sale in it, but its history lives on in the folder: it stays until the item is moved.
        call(delete(BASE + "/folders/" + pouch), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("still has 1 item")));
        call(put(BASE + "/items/" + item), "{\"categoryId\":" + tin + ",\"name\":\"Sold Once\",\"price\":10.00}")
                .andExpect(jsonPath("$.active").value(false));
        call(delete(BASE + "/folders/" + pouch), null).andExpect(status().isNoContent());
        call(get(BASE + "/items"), null).andExpect(jsonPath("$[0].items[0].name").value("Sold Once"));
    }

    @Test
    void aFolderOffTheGridIsHiddenAtTheTillButStillInTheDashboardWithItsItems() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long item = item(pouch, "Hidden Away", "10.00");
        call(put(BASE + "/folders/" + pouch), "{\"name\":\"Pouch\",\"tab\":null}").andExpect(jsonPath("$.tab").doesNotExist());

        // The grid has no tile for it, so there is no way to it from the till's tabs.
        mockMvc.perform(get("/api/v1/catalog/tabs").with(till())).andExpect(jsonPath("$[0].folders").value(empty()));
        // The dashboard still lists the folder and its item, flagged off the grid.
        call(get(BASE + "/items"), null).andExpect(jsonPath("$[0].name").value("Pouch")).andExpect(jsonPath("$[0].tab").doesNotExist())
                .andExpect(jsonPath("$[0].items[0].name").value("Hidden Away")).andExpect(jsonPath("$[0].items[0].active").value(true));
        // Hiding the tile is all it does: the item's own listing and sale are untouched (a cart already holding it still works).
        mockMvc.perform(get("/api/v1/catalog/categories/" + pouch + "/items").with(till()))
                .andExpect(jsonPath("$.items[*].name").value(contains("Hidden Away")));
        sell(item);
        // Put back on a tab it shows again.
        call(put(BASE + "/folders/" + pouch), "{\"name\":\"Pouch\",\"tab\":\"Selling Items\"}");
        mockMvc.perform(get("/api/v1/catalog/tabs").with(till())).andExpect(jsonPath("$[0].folders[0].name").value("Pouch"))
                .andExpect(jsonPath("$[0].folders[0].itemCount").value(1));
    }

    @Test
    void aTabWithNoFoldersShowsEmptyAtTheTillAndOnTheDashboardAndGoesWhenDeleted() throws Exception {
        long pouch = folder("Pouch", "Selling Items");
        long empty = ((Number) JsonPath.read(call(post(BASE + "/folders"), "{\"name\":\"Temp\",\"tab\":\"Spare\"}")
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        call(put(BASE + "/folders/" + empty), "{\"name\":\"Temp\",\"tab\":null}").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/catalog/tabs").with(till()))
                .andExpect(jsonPath("$[*].name").value(contains("Selling Items", "Spare")))
                .andExpect(jsonPath("$[1].folders").value(empty())).andExpect(jsonPath("$[0].folders[0].categoryId").value((int) pouch));
        call(get(BASE + "/tabs"), null).andExpect(jsonPath("$[1].name").value("Spare")).andExpect(jsonPath("$[1].folders").value(0));

        long spare = jdbc.queryForObject("SELECT id FROM quickgrid_tab WHERE name = 'Spare'", Long.class);
        call(delete(BASE + "/tabs/" + spare), null).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/catalog/tabs").with(till())).andExpect(jsonPath("$[*].name").value(not(hasItem("Spare"))));
    }

    // ---- helpers ----

    private long folder(String name, String tab) throws Exception {
        return idOf(call(post(BASE + "/folders"), "{\"name\":\"" + name + "\",\"tab\":\"" + tab + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    private long item(long folderId, String name, String price) throws Exception {
        return idOf(call(post(BASE + "/items"), "{\"categoryId\":" + folderId + ",\"name\":\"" + name + "\",\"price\":" + price + "}")
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    private long group(long itemId, String name, int chooseExactly) throws Exception {
        return idOf(call(post(BASE + "/items/" + itemId + "/modifier-groups"), "{\"name\":\"" + name + "\",\"chooseExactly\":" + chooseExactly + "}")
                .andExpect(status().isCreated()).andReturn().getResponse());
    }

    private long seasonalItem(long folder, String name, LocalDate from, LocalDate until) throws Exception {
        return idOf(call(post(BASE + "/items"), seasonalJson(folder, name, from, until)).andExpect(status().isCreated())
                .andReturn().getResponse());
    }

    private boolean seasonalNow(long folder, String name, LocalDate from, LocalDate until) throws Exception {
        return JsonPath.read(call(post(BASE + "/items"), seasonalJson(folder, name, from, until)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.seasonalNow");
    }

    private static String seasonalJson(long folder, String name, LocalDate from, LocalDate until) {
        return "{\"categoryId\":%d,\"name\":\"%s\",\"price\":10.00,\"seasonal\":true,%s\"seasonalUntil\":\"%s\"}"
                .formatted(folder, name, from == null ? "" : "\"seasonalFrom\":\"" + from + "\",", until);
    }

    private static long idOf(MockHttpServletResponse response) throws Exception {
        return ((Number) JsonPath.read(response.getContentAsString(), "$.id")).longValue();
    }

    private static String saleBody(long itemId) {
        return """
                {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":10.00}]}""".formatted(UUID.randomUUID(), itemId);
    }

    /** A sale of one of the item at 10.00 (every item here costs that), paid in full; the order id. */
    private String sell(long itemId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(till()).contentType(MediaType.APPLICATION_JSON)
                        .content(saleBody(itemId)))
                .andReturn().getResponse().getContentAsString();
        Object id = JsonPath.read(body, "$.id");
        return String.valueOf(id);
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String json) throws Exception {
        request.with(admin());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor till() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    /** {@code callers} calls released together, each given 30 seconds; the answers in caller order. */
    private List<MockHttpServletResponse> race(int callers, CallerCall call) throws Exception {
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            int caller = i;
            calls.add(() -> call.run(caller));
        }
        return raceAll(calls);
    }

    @SafeVarargs
    private List<MockHttpServletResponse> raceAll(Callable<MockHttpServletResponse>... calls) throws Exception {
        return raceAll(List.of(calls));
    }

    private List<MockHttpServletResponse> raceAll(List<Callable<MockHttpServletResponse>> calls) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<MockHttpServletResponse>> running = new ArrayList<>();
            for (Callable<MockHttpServletResponse> call : calls) {
                running.add(pool.submit(() -> {
                    go.await();
                    return call.call();
                }));
            }
            go.countDown();
            List<MockHttpServletResponse> answers = new ArrayList<>();
            for (Future<MockHttpServletResponse> one : running) {
                answers.add(one.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface CallerCall {
        MockHttpServletResponse run(int caller) throws Exception;
    }
}
