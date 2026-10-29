package com.counterline.pos.catalog;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The demo-catalogue initializer runs the real {@code db/demo/R__demo_catalog.sql} against PostgreSQL
 * (built directly, as the shared context has it disabled, and run in a transaction as the application
 * does). The representative facts asserted here are read from that file; the file stays the one source.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DemoCatalogInitializerIntegrationTest {

    @Autowired
    ApplicationContext context;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void emptyCatalogue() {
        jdbc.execute("TRUNCATE item, category, quickgrid_tab, promotion, discount RESTART IDENTITY CASCADE");
    }

    @Test
    void isNotRegisteredUnlessEnabled() {
        assertThat(context.getBeanNamesForType(DemoCatalogInitializer.class)).isEmpty();
        assertThat(count("category")).isZero();
        assertThat(count("promotion")).isZero();
    }

    @Test
    void refusesTheLocalProfileAndChangesNothing() {
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");

        assertThatThrownBy(() -> run(new DemoCatalogInitializer(jdbc, local)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("cannot be used with the local profile");
        assertThat(count("quickgrid_tab")).isZero();
        assertThat(count("discount")).isZero();
    }

    @Test
    void loadsTheDemoCatalogueExactlyAndNothingElse() throws Exception {
        List<String> untouched = List.of("outlet", "voucher_batch", "voucher", "admin_user", "staff", "customer", "sale_order");
        Map<String, Long> before = new java.util.LinkedHashMap<>();
        untouched.forEach(table -> before.put(table, count(table)));
        run(initializer());

        assertThat(jdbc.queryForList("SELECT name FROM quickgrid_tab ORDER BY sort_order", String.class))
                .containsExactly("Selling Items", "Promotional", "Sample Items");
        assertThat(count("category")).isEqualTo(18);
        Map<String, Object> giftBox = jdbc.queryForMap("""
                SELECT t.name AS tab, c.grid_row, c.grid_col, c.counts_for_promotions FROM category c
                JOIN quickgrid_tab t ON t.id = c.tab_id WHERE c.name = 'General Gift Box'""");
        assertThat(giftBox).containsEntry("tab", "Selling Items").containsEntry("grid_row", 1)
                .containsEntry("grid_col", 4).containsEntry("counts_for_promotions", true);

        // The gift boxes: CHOOSE 5 of nine classic flavours, CHOOSE 10 of those plus Seaweed Wasabi.
        assertThat(giftBoxPrice("General Gift Box (Paper Can)")).isEqualByComparingTo("58.00");
        assertThat(giftBoxPrice("General Gift Box (Baby Can)")).isEqualByComparingTo("70.00");
        assertThat(jdbc.queryForList("""
                SELECT g.name || ':' || g.choose_exactly FROM modifier_group g ORDER BY g.choose_exactly""", String.class))
                .containsExactly("General Gift Box Paper Can:5", "General Gift Box Baby Can:10");
        List<String> paperCan = options("General Gift Box Paper Can");
        List<String> babyCan = options("General Gift Box Baby Can");
        assertThat(paperCan).hasSize(9).allMatch(name -> name.startsWith("GB PC ")).contains("GB PC Caramel", "GB PC Dark Choc")
                .doesNotContain("GB PC Seaweed Wasabi");
        assertThat(babyCan).hasSize(10).allMatch(name -> name.startsWith("GB BC ")).contains("GB BC Caramel", "GB BC Seaweed Wasabi");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM modifier_option WHERE active AND price = 0", Long.class)).isEqualTo(19);
        // The till offers it: the gift box opens the item dialog with its group and options.
        long boxId = jdbc.queryForObject("SELECT id FROM item WHERE name = 'General Gift Box (Paper Can)'", Long.class);
        mockMvc.perform(get("/api/v1/catalog/items/" + boxId + "/options").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].name").value("General Gift Box Paper Can"))
                .andExpect(jsonPath("$.groups[0].chooseExactly").value(5))
                .andExpect(jsonPath("$.groups[0].options[*].name").value(hasItems("GB PC Caramel", "GB PC Cheese")));

        // Promotions and discounts.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM promotion WHERE active", Long.class)).isEqualTo(9);
        Map<String, Object> buy3 = jdbc.queryForMap(
                "SELECT type, buy_quantity, members_only FROM promotion WHERE name = 'Buy 3 Free 1 Promo'");
        assertThat(buy3).containsEntry("type", "BUY_X_GET_FREE").containsEntry("buy_quantity", 3).containsEntry("members_only", true);
        assertThat(jdbc.queryForObject("SELECT seasonal_items_only FROM promotion WHERE name = 'Seasonal Flavour Promo'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM item WHERE promotion_id IS NOT NULL", Long.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM discount WHERE active", Long.class)).isEqualTo(9);
        assertThat(jdbc.queryForMap("SELECT kind, value, scope, not_for_members FROM discount WHERE name = 'Staff Discount'"))
                .containsEntry("kind", "PERCENT").containsEntry("scope", "BOTH").containsEntry("not_for_members", true);
        assertThat(jdbc.queryForObject("SELECT counts_as_voucher FROM discount WHERE name = 'RM10 Voucher'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT requires_approval FROM discount WHERE name = 'Marketing Department Special Approved'",
                Boolean.class)).isTrue();

        // Nothing else: no outlets, vouchers, accounts, staff, customers or sales are added or removed.
        untouched.forEach(table -> assertThat(count(table)).as(table).isEqualTo(before.get(table)));
    }

    @Test
    void aRerunKeepsEveryRowAndRetiresInsteadOfDeleting() {
        run(initializer());
        String snapshot = snapshot();
        // Something head office added outside the demo range: a rerun must not delete it.
        jdbc.update("INSERT INTO category (name) VALUES ('Legacy Folder')");
        jdbc.update("INSERT INTO item (category_id, name, price, sort_order) SELECT id, 'Legacy Item', 5.00, 1 FROM category WHERE name = 'Legacy Folder'");

        run(initializer());
        run(initializer());

        assertThat(snapshot()).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT active FROM item WHERE name = 'Legacy Item'", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT tab_id FROM category WHERE name = 'Legacy Folder'", Long.class)).isNull();
        assertThat(count("category")).isEqualTo(19);
    }

    @Test
    void aFailingScriptIsRolledBackCompletely() {
        String broken = """
                INSERT INTO quickgrid_tab (name, sort_order) VALUES ('Rollback Tab', 99);
                INSERT INTO discount (name, kind, value, scope, sort_order) VALUES ('Rollback Discount', 'PERCENT', 5, 'ORDER', 1);
                SELECT 1 / 0;
                """;
        DemoCatalogInitializer failing = new DemoCatalogInitializer(jdbc, new MockEnvironment(),
                new ByteArrayResource(broken.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> run(failing)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing was changed");
        assertThat(count("quickgrid_tab")).isZero();
        assertThat(count("discount")).isZero();
    }

    private DemoCatalogInitializer initializer() {
        return new DemoCatalogInitializer(jdbc, new MockEnvironment());
    }

    private void run(DemoCatalogInitializer initializer) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> initializer.run(null));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private java.math.BigDecimal giftBoxPrice(String name) {
        return jdbc.queryForObject("SELECT price FROM item WHERE name = ? AND active", java.math.BigDecimal.class, name);
    }

    private List<String> options(String group) {
        return jdbc.queryForList("""
                SELECT o.name FROM modifier_option o JOIN modifier_group g ON g.id = o.group_id
                WHERE g.name = ? AND o.active ORDER BY o.sort_order""", String.class, group);
    }

    /** Ids, names and values of the rows the script owns, to compare before and after a rerun. */
    private String snapshot() {
        return List.of(
                jdbc.queryForList("SELECT id || name || sort_order FROM quickgrid_tab ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || coalesce(tab_id::text, '-') || coalesce(grid_row::text, '-') || coalesce(grid_col::text, '-') || counts_for_promotions FROM category WHERE name <> 'Legacy Folder' ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || price || sort_order || active || coalesce(promotion_id::text, '-') FROM item WHERE name <> 'Legacy Item' ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || choose_exactly FROM modifier_group ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || sort_order || active FROM modifier_option ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || active FROM promotion ORDER BY id", String.class),
                jdbc.queryForList("SELECT id || name || value || active || not_for_members || counts_as_voucher FROM discount ORDER BY id", String.class)).toString();
    }
}
