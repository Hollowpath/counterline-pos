package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.LoyaltyService.Settlement;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member's points are a 32-bit balance. A sale, a refund or an adjustment that would take it past
 * {@link Integer#MAX_VALUE} is refused with a 4xx before anything is recorded; one that lands exactly on it is fine.
 * Items (catalog-fixture.sql): 1 Pouch Cheese, set to 16.00 here; RM10 earns a point, so RM32.00 earns 3 and RM16.00 earns 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class LoyaltyBalanceLimitIntegrationTest {

    private static final String LIMIT_MESSAGE = "The member's points would go over the limit";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    LoyaltyService loyalty;

    private Long cashierId;
    private Long managerId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        // No free gifts, so a member with many points is not made to redeem them.
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD', 'POINTS_REDEMPTION')");
        jdbc.update("UPDATE item SET price = 16.00 WHERE id = 1");
        cashierId = staffRepository.save(new Staff("Limit Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Limit Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        Instant joined = Instant.now().minus(Duration.ofDays(800));
        memberId = jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES ('Nur', 'Aisyah', ?, ?, ?) RETURNING id""", Long.class,
                Date.valueOf(LocalDate.of(1995, 3, 15)), Timestamp.from(joined), Timestamp.from(joined));
    }

    @Test
    void aSettlementFitsUpToIntegerMaxValueAndNotOnePointBeyond() {
        assertThat(new Settlement(Integer.MAX_VALUE - 5, 5, 0).after()).isEqualTo(Integer.MAX_VALUE);
        assertThat(new Settlement(Integer.MAX_VALUE, 0, 0).after()).isEqualTo(Integer.MAX_VALUE);
        assertThat(new Settlement(Integer.MIN_VALUE, 0, 0).after()).isEqualTo(Integer.MIN_VALUE);
        assertThat(new Settlement(0, 999_999_999, 0).after()).isEqualTo(999_999_999);

        assertThatThrownBy(() -> new Settlement(Integer.MAX_VALUE - 5, 6, 0))
                .isInstanceOf(BusinessRuleException.class).hasMessage(LIMIT_MESSAGE);
        // The points are earned before they are spent, so this too is past the limit for a moment and is refused.
        assertThatThrownBy(() -> new Settlement(Integer.MAX_VALUE, 1, 1)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new Settlement(Integer.MIN_VALUE, 0, 1)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void thePointsASaleEarnsAreExactAtTheMoneyCapAndRefusedBeyondIt() {
        // The most a sale may be (RM9,999,999,999.99) earns 999,999,999 points, well inside an int.
        assertThat(LoyaltyService.pointsEarned(new BigDecimal("9999999999.99"))).isEqualTo(999_999_999);
        assertThat(LoyaltyService.pointsEarned(new BigDecimal("9.99"))).isZero();
        assertThatThrownBy(() -> LoyaltyService.pointsEarned(new BigDecimal("99999999999999.99")))
                .isInstanceOf(BusinessRuleException.class).hasMessage(LIMIT_MESSAGE);
    }

    @Test
    void anAdjustmentCanLandOnIntegerMaxValueAndNoFurtherAndRecordsNothingWhenRefused() {
        Instant now = Instant.now();
        assertThat(loyalty.adjust(memberId, "Nur Aisyah", Integer.MAX_VALUE - 5, "Seed", "Head Office", now))
                .isEqualTo(Integer.MAX_VALUE - 5);
        assertThat(loyalty.adjust(memberId, "Nur Aisyah", 5, "To the limit", "Head Office", now)).isEqualTo(Integer.MAX_VALUE);

        assertThatThrownBy(() -> loyalty.adjust(memberId, "Nur Aisyah", 1, "One too many", "Head Office", now))
                .isInstanceOf(BusinessRuleException.class).hasMessage(LIMIT_MESSAGE);
        assertThatThrownBy(() -> loyalty.adjust(memberId, "Nur Aisyah", Integer.MAX_VALUE, "Far too many", "Head Office", now))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(loyalty.balance(memberId)).isEqualTo(Integer.MAX_VALUE);
        assertThat(entries()).isEqualTo(2);
        // Taking points away from the limit still works.
        assertThat(loyalty.adjust(memberId, "Nur Aisyah", -10, "Back down", "Head Office", now)).isEqualTo(Integer.MAX_VALUE - 10);
    }

    @Test
    void aSaleThatLandsOnTheLimitIsRecordedAndOneBeyondIsRefusedBeforeAnythingIsSaved() throws Exception {
        loyalty.adjust(memberId, "Nur Aisyah", Integer.MAX_VALUE - 3, "Seed", "Head Office", Instant.now());

        // RM32.00 earns 3 points: exactly Integer.MAX_VALUE.
        Integer orderId = JsonPath.read(sale(2, "32.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(loyalty.balance(memberId)).isEqualTo(Integer.MAX_VALUE);
        assertThat(count("sale_order")).isEqualTo(1);
        assertThat(entries()).isEqualTo(2);

        // One more point does not fit: a client error, and nothing of the sale is left behind.
        sale(1, "16.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(LIMIT_MESSAGE));
        assertThat(count("sale_order")).isEqualTo(1);
        assertThat(count("payment")).isEqualTo(1);
        assertThat(entries()).isEqualTo(2);
        assertThat(loyalty.balance(memberId)).isEqualTo(Integer.MAX_VALUE);

        // The cart's preview says so too, rather than failing.
        mockMvc.perform(post("/api/v1/orders/quote").with(cashier()).contentType(MediaType.APPLICATION_JSON).content(
                        saleJson(1, "16.00"))).andExpect(status().isUnprocessableContent());

        // Refunding the sale that reached the limit takes its points back.
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund").with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Wrong flavour\"}"))
                .andExpect(status().isOk());
        assertThat(loyalty.balance(memberId)).isEqualTo(Integer.MAX_VALUE - 3);
    }

    @Test
    void aSaleThatWouldCrossTheLimitByTwoPointsIsRefusedWholeNotPartlyRecorded() throws Exception {
        loyalty.adjust(memberId, "Nur Aisyah", Integer.MAX_VALUE - 2, "Seed", "Head Office", Instant.now());

        sale(2, "32.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(LIMIT_MESSAGE));

        assertThat(count("sale_order")).isZero();
        assertThat(count("payment")).isZero();
        assertThat(entries()).isEqualTo(1);
        assertThat(loyalty.balance(memberId)).isEqualTo(Integer.MAX_VALUE - 2);
    }

    private ResultActions sale(int quantity, String amount) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                .content(saleJson(quantity, amount)));
    }

    private String saleJson(int quantity, String amount) {
        return """
                {"clientRequestId":"%s","customerId":%d,"lines":[{"itemId":1,"quantity":%d}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}""".formatted(UUID.randomUUID(), memberId, quantity, amount);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private int entries() {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty_entry WHERE customer_id = ?", Integer.class, memberId);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("name", "Limit Cashier"));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString())
                        .claim("role", "MANAGER").claim("name", "Limit Manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
