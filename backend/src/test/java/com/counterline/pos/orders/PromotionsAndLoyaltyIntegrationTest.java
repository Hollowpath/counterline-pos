package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.TokenService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Promotional items and member points end to end. Fixtures: catalog-fixture.sql (1 Pouch Cheese
 * 16.00 and 4 Tin Caramel 29.00 count towards "Buy 3"; 5 Promo Tin 26.00 does not) and
 * promotions-fixture.sql (promotional items 21–29 and 31).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class PromotionsAndLoyaltyIntegrationTest {

    private static final ZoneId OUTLET = ZoneId.of("Asia/Kuala_Lumpur");
    private static final String NEEDS_MEMBER = "Add member";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    TokenService tokenService;

    private Long cashierId;
    private Long managerId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // orders, loyalty, registers
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        // Born six months from now, so never in this month.
        memberId = member("Nur Aisyah", LocalDate.now(OUTLET).plusMonths(6).withYear(1995).withDayOfMonth(15));
    }

    // ---- Member required ----

    @Test
    void promotionalItemsNeedAMemberOnTheSaleToBePaid() throws Exception {
        sell(null, lines(4, 3, 21, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(NEEDS_MEMBER));
        assertThat(count("sale_order")).isZero();

        // Placing an order is never refused: the rules apply when it is paid.
        place(null, lines(4, 3, 21, 1)).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
        assertThat(count("sale_order")).isOne();
    }

    @Test
    void aPromotionalLineIsDeductedInFullAndCannotTakeADiscount() throws Exception {
        pointsAccumulate();
        sell(memberId, lines(4, 3, 21, 1), "87.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(93.5))
                .andExpect(jsonPath("$.discountTotal").value(6.5))
                .andExpect(jsonPath("$.total").value(87.0))
                .andExpect(jsonPath("$.lines[1].itemName").value("B3 F1 BC Caramel"))
                .andExpect(jsonPath("$.lines[1].discounts[0].name").value("Buy 3 Free 1 Promo"))
                .andExpect(jsonPath("$.lines[1].discounts[0].promotion").value(true))
                .andExpect(jsonPath("$.lines[1].lineTotal").value(0.0));

        long discountId = jdbc.queryForObject("""
                INSERT INTO discount (name, kind, value, scope, sort_order) VALUES ('Test 10%', 'PERCENT', 10, 'BOTH', 99)
                ON CONFLICT (name) DO UPDATE SET active = TRUE RETURNING id
                """, Long.class);
        checkout(memberId, "PAY", "[{\"itemId\":4,\"quantity\":3},{\"itemId\":21,\"quantity\":1,\"discountIds\":[" + discountId + "]}]",
                "[]", "81.15")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("B3 F1 BC Caramel is promotional and cannot take a discount"));
        // An order discount (the % tab) gives no Buy 3 Free 1 can; without it, it is 10% of 87.00.
        checkout(memberId, "PAY", lines(4, 3, 21, 1), "[" + discountId + "]", "78.30")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Discount on: remove B3 F1 BC Caramel")));
        checkout(memberId, "PAY", lines(4, 3, null, 0), "[" + discountId + "]", "78.30")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderDiscounts[0].amount").value(8.7));

        // The dashboard counts what was given away: the free can and the discount, each on its own.
        String today = LocalDate.now(OUTLET).toString();
        // A dashboard session exists only for a manager with a dashboard login.
        jdbc.update("UPDATE staff SET email = ?, password_hash = ? WHERE id = ?", "manager@promotions.test", "unused-hash", managerId);
        String manager = tokenService.issueDashboard(staffRepository.findById(managerId).orElseThrow()).value();
        mockMvc.perform(get("/api/v1/dashboard/overview").param("from", today).param("to", today)
                        .header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.freeItems").value(1))
                .andExpect(jsonPath("$.current.freeItemsValue").value(6.5))
                .andExpect(jsonPath("$.current.discounts").value(8.7))
                .andExpect(jsonPath("$.current.newMembers").value(1))
                .andExpect(jsonPath("$.promotions[0].name").value("Buy 3 Free 1 Promo"))
                .andExpect(jsonPath("$.promotions[0].count").value(1))
                .andExpect(jsonPath("$.discounts[0].name").value("Test 10%"))
                .andExpect(jsonPath("$.discounts[0].count").value(1))
                .andExpect(jsonPath("$.discounts[0].amount").value(8.7));
    }

    // ---- Buy 3 Free 1 ----

    @Test
    void buyThreeFreeOneGivesOneFreeItemForEveryThreeQualifyingItems() throws Exception {
        pointsAccumulate();
        toteBagNotRunning();
        // 2 Pouch Cheese + 1 Tin Caramel = 3 qualifying; the Promo Tin does not count.
        sell(memberId, "[{\"itemId\":1,\"quantity\":2},{\"itemId\":4,\"quantity\":1},{\"itemId\":21,\"quantity\":1}]", "61.00")
                .andExpect(status().isCreated());

        sell(memberId, "[{\"itemId\":1,\"quantity\":2},{\"itemId\":5,\"quantity\":1},{\"itemId\":21,\"quantity\":1}]", "58.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Buy 3 Free 1 Promo"));

        sell(memberId, lines(4, 6, 21, 2), "174.00").andExpect(status().isCreated());
        sell(memberId, lines(4, 5, 21, 2), "145.00").andExpect(status().isUnprocessableContent());
    }

    @Test
    void aMemberMustBeGivenEveryFreeItemTheyHaveEarned() throws Exception {
        pointsAccumulate();
        toteBagNotRunning();
        sell(memberId, lines(4, 6, 21, 1), "174.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 1 more Buy 3 Free 1 Promo"));
        quote(memberId, lines(4, 3, null, 0)).andExpect(jsonPath("$.problems[0]").value("Select 1 Buy 3 Free 1 Promo"));
        // Placed as it is; the gift is due when it's paid.
        place(memberId, lines(4, 3, null, 0)).andExpect(status().isCreated());

        // In the birth month, the birthday can is the other choice.
        Long birthdayMember = member("Ahmad Faiz", LocalDate.now(OUTLET).withYear(1990).withDayOfMonth(1));
        sell(birthdayMember, lines(4, 3, null, 0), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Select 1 Buy 3 Free 1 Promo / Birthday Reward")));

        // The seasonal flavour's free baby can too.
        sell(memberId, "[{\"itemId\":4,\"quantity\":2},{\"itemId\":30,\"quantity\":1},{\"itemId\":21,\"quantity\":1}]", "72.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 1 Seasonal Flavour Promo"));

        // Without a member there is nothing to give; the bags, tote bag and once-only rewards are optional.
        sell(null, lines(4, 3, null, 0), "87.00").andExpect(status().isCreated());
        sell(memberId, bought(6, 21, 2), "174.00").andExpect(status().isCreated());
    }

    @Test
    void theStaffDiscountCantBeGivenOnAMembersSale() throws Exception {
        pointsAccumulate();
        long staffDiscount = jdbc.queryForObject("""
                INSERT INTO discount (name, kind, value, scope, sort_order, not_for_members)
                VALUES ('Staff Discount', 'PERCENT', 30, 'BOTH', 98, TRUE)
                ON CONFLICT (name) DO UPDATE SET active = TRUE, not_for_members = TRUE RETURNING id
                """, Long.class);
        checkout(memberId, "PAY", lines(1, 1, null, 0), "[" + staffDiscount + "]", "11.20")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Staff Discount: not for members"));
        checkout(null, "PAY", lines(1, 1, null, 0), "[" + staffDiscount + "]", "11.20").andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[?(@.name == 'Staff Discount')].notForMembers").value(contains(true)));
    }

    // ---- Spend RMx ----

    @Test
    void spendPromotionsNeedTheSaleTotalAndGiveOneFreeBagAtMost() throws Exception {
        pointsAccumulate();
        sell(memberId, lines(1, 2, 26, 1), "32.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Spend RM50 Free Bag (S): min RM50.00"));

        // RM50 → the small bag; RM80 → the medium one; never both. (4 pouches also earn the Buy 3
        // Free 1 baby can, item 21, which must be given.)
        sell(memberId, pouches(4, 26, 1, 21, 1), "64.00").andExpect(status().isCreated());
        sell(memberId, pouches(4, 27, 1, 21, 1), "64.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Spend RM80 Free Bag (M): min RM80.00")));
        sell(memberId, bought(3, 21, 1, 26, 1, 27, 1), "87.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("1 free recycle bag only"));
        sell(memberId, bought(3, 21, 1, 27, 1), "87.00").andExpect(status().isCreated());
    }

    @Test
    void theFreeRecycleBagsNeedNoMembership() throws Exception {
        sell(null, lines(1, 4, 26, 1), "64.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[1].discounts[0].name").value("Spend RM50 Free Bag (S)"));
        sell(null, lines(1, 2, 26, 1), "32.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Spend RM50 Free Bag (S): min RM50.00"));
        // The tote bag and every other promotion still need one.
        sell(null, bought(6, 28, 1), "174.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(NEEDS_MEMBER));
        sell(null, lines(1, 4, 21, 1), "64.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(NEEDS_MEMBER));
    }

    // ---- Points ----

    @Test
    void membersEarnAPointPerRm10AndCanSpendWhatTheSaleEarns() throws Exception {
        freeGiftsNotRunning();
        // 61.00 earns 6: not enough for a 7-point redemption with an empty balance.
        sell(memberId, "[{\"itemId\":1,\"quantity\":2},{\"itemId\":4,\"quantity\":1},{\"itemId\":23,\"quantity\":1}]", "61.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Not enough points (7 needed, 6 available)"));

        // 87.00 earns 8: previous 0, redeemed 7, earned 8, current 1 (as on the outlet's receipts).
        sell(memberId, lines(4, 3, 23, 1), "87.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[1].discounts[0].name").value("Member Redemption"))
                .andExpect(jsonPath("$.loyalty.before").value(0))
                .andExpect(jsonPath("$.loyalty.earned").value(8))
                .andExpect(jsonPath("$.loyalty.redeemed").value(7))
                .andExpect(jsonPath("$.loyalty.after").value(1));
        assertThat(balance()).isEqualTo(1);

        sell(memberId, lines(1, 1, null, 0), "16.00").andExpect(jsonPath("$.loyalty.before").value(1))
                .andExpect(jsonPath("$.loyalty.after").value(2));
        assertThat(balance()).isEqualTo(2);
    }

    @Test
    void nonMembersEarnNothingAndARefundReversesThePoints() throws Exception {
        freeGiftsNotRunning();
        sell(null, lines(4, 1, null, 0), "29.00").andExpect(jsonPath("$.loyalty").doesNotExist());

        String body = sell(memberId, lines(4, 3, 23, 1), "87.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(balance()).isEqualTo(1);

        Integer orderId = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Damaged\"}"))
                .andExpect(status().isOk());

        assertThat(balance()).isZero();
        assertThat(jdbc.queryForList("SELECT type FROM loyalty_entry ORDER BY id", String.class))
                .containsExactly("EARN", "REDEEM", "REVERSE_REDEEM", "REVERSE_EARN");
    }

    @Test
    void aPlacedOrderSettlesPointsWhenItIsPaid() throws Exception {
        jdbc.update("INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (?, 7, now())", memberId);
        String body = place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(balance()).isEqualTo(7);

        Integer orderId = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"%s\",\"payments\":[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":16.00}]}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loyalty.redeemed").value(7))
                .andExpect(jsonPath("$.loyalty.after").value(1));
        assertThat(balance()).isEqualTo(1);
    }

    @Test
    void pointsAreNotCarriedForwardOnceTheyReachTheRedemptionCost() throws Exception {
        freeGiftsNotRunning();
        setBalance(6);
        // 6 + 8 earned = 14: two Member FOC items must be redeemed on the spot.
        sell(memberId, lines(4, 3, null, 0), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 2 Member FOC"));
        sell(memberId, lines(4, 3, 23, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 1 more Member FOC"));
        quote(memberId, lines(4, 3, null, 0)).andExpect(jsonPath("$.problems", hasSize(1)))
                .andExpect(jsonPath("$.loyalty.after").value(14));

        sell(memberId, lines(4, 3, 23, 2), "87.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.loyalty.redeemed").value(14))
                .andExpect(jsonPath("$.loyalty.after").value(0));
        assertThat(balance()).isZero();
    }

    @Test
    void payingAPlacedOrderChecksThePointsAgain() throws Exception {
        String body = place(memberId, lines(1, 1, null, 0)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // Another sale took the member to 6 since; this order's 1 point would make 7.
        setBalance(6);

        Integer orderId = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/payments").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"%s\",\"payments\":[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":16.00}]}"
                                .formatted(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Member FOC")));
        assertThat(balance()).isEqualTo(6);
    }

    // ---- Once-only rewards ----

    @Test
    void theRegistrationRewardIsGivenOncePerMemberWithin24HoursOfSigningUp() throws Exception {
        sell(memberId, lines(1, 1, 24, 1), "16.00").andExpect(status().isCreated());

        sell(memberId, lines(1, 1, 24, 1), "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Registration Reward Promo: already used"));

        Long lateMember = member("Lim Wei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofHours(25)));
        sell(lateMember, lines(1, 1, 24, 1), "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Registration Reward Promo: expired"));
    }

    @Test
    void theGoogleReviewRewardIsGivenOncePerMemberAtEachOutlet() throws Exception {
        Long longTimeMember = member("Lim Wei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        sell(longTimeMember, lines(1, 1, 31, 1), "16.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[1].discounts[0].name").value("Google Review Reward"));

        sell(longTimeMember, lines(1, 1, 31, 1), "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Google Review Reward: already used here"));

        // Each outlet is reviewed on its own.
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO NOTHING");
        long second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        jdbc.update("UPDATE staff SET outlet_id = ? WHERE id = ?", second, cashierId);
        jdbc.update("INSERT INTO register_session (outlet_id, opened_at, opened_by) VALUES (?, now(), ?)", second, cashierId);
        mockMvc.perform(post("/api/v1/orders").with(cashierAt(second)).contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","customerId":%d,"lines":%s,"payments":[{"paymentMethodId":2,"reference":"ref","amount":16.00}]}"""
                        .formatted(UUID.randomUUID(), longTimeMember, lines(1, 1, 31, 1))))
                .andExpect(status().isCreated());
    }

    // ---- Edit Order ----

    @Test
    void aPlacedOrderIsEditedInTheCartAndKeepsItsNumber() throws Exception {
        pointsAccumulate();
        Long longTimeMember = member("Lim Wei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        String placed = place(longTimeMember, lines(1, 1, 31, 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer orderId = JsonPath.read(placed, "$.id");
        String number = JsonPath.read(placed, "$.orderNumber");

        mockMvc.perform(get("/api/v1/orders/" + orderId + "/cart").with(cashier())).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value(number))
                .andExpect(jsonPath("$.customer.id").value(longTimeMember))
                .andExpect(jsonPath("$.lines[0].itemId").value(1))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(16.0))
                .andExpect(jsonPath("$.lines[0].countsForPromotions").value(true))
                .andExpect(jsonPath("$.lines[1].promotion.name").value("Google Review Reward"));
        // Its own Google Review reward isn't "already had" while it is being edited.
        mockMvc.perform(post("/api/v1/orders/quote").param("editing", orderId.toString()).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","customerId":%d,"lines":%s,"payments":[]}"""
                        .formatted(UUID.randomUUID(), longTimeMember, lines(1, 2, 31, 1))))
                .andExpect(jsonPath("$.problems").isEmpty());

        // A second pouch, then paid: the same order, now closed. A retry returns it as paid.
        String edit = """
                {"clientRequestId":"%s","mode":"PAY","customerId":%d,"lines":%s,
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":32.00}]}"""
                .formatted(UUID.randomUUID(), longTimeMember, lines(1, 2, 31, 1));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/v1/orders/" + orderId).with(cashier()).contentType(MediaType.APPLICATION_JSON).content(edit))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(orderId))
                    .andExpect(jsonPath("$.orderNumber").value(number))
                    .andExpect(jsonPath("$.status").value("CLOSED"))
                    .andExpect(jsonPath("$.total").value(32.0))
                    .andExpect(jsonPath("$.lines", hasSize(2)));
        }
        assertThat(count("sale_order")).isEqualTo(1);
        assertThat(count("sale_order_line")).isEqualTo(2);
        mockMvc.perform(get("/api/v1/orders/" + orderId + "/cart").with(cashier())).andExpect(status().isUnprocessableContent());
    }

    // ---- Member perks (the cart, as soon as a member is added) ----

    @Test
    void perksShowPointsTheBirthdayMonthAndANewMembersGift() throws Exception {
        setBalance(5);
        perks(memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(5))
                .andExpect(jsonPath("$.redemptionCost").value(7))
                .andExpect(jsonPath("$.birthdayMonth").value(false))
                .andExpect(jsonPath("$.newMemberGifts[0].promotion").value("Registration Reward Promo"))
                .andExpect(jsonPath("$.newMemberGifts[0].items[0]").value("Registration Reward MBP"))
                .andExpect(jsonPath("$.newMemberGifts[0].availableUntil").exists());

        // Taken: no longer offered.
        sell(memberId, lines(1, 1, 24, 1), "16.00").andExpect(status().isCreated());
        perks(memberId).andExpect(jsonPath("$.newMemberGifts").isEmpty());

        Long birthdayMember = member("Ahmad Faiz", LocalDate.now(OUTLET).withYear(1990).withDayOfMonth(1),
                Instant.now().minus(Duration.ofDays(3)));
        perks(birthdayMember).andExpect(jsonPath("$.birthdayMonth").value(true))
                .andExpect(jsonPath("$.points").value(0))
                .andExpect(jsonPath("$.newMemberGifts").isEmpty());

        perks(999_999L).andExpect(status().isNotFound());
    }

    /** Lines: n × Tin Caramel (29.00, counts towards Buy 3), then promotional items as {id: quantity}. */
    private static String bought(int tins, int... promoIdsAndQuantities) {
        return items(4, tins, promoIdsAndQuantities);
    }

    /** Lines: n × Pouch Cheese (16.00, counts towards Buy 3), then promotional items as {id: quantity}. */
    private static String pouches(int pouches, int... promoIdsAndQuantities) {
        return items(1, pouches, promoIdsAndQuantities);
    }

    private static String items(int itemId, int quantity, int... promoIdsAndQuantities) {
        StringBuilder json = new StringBuilder("[{\"itemId\":" + itemId + ",\"quantity\":" + quantity + "}");
        for (int k = 0; k < promoIdsAndQuantities.length; k += 2) {
            json.append(",{\"itemId\":").append(promoIdsAndQuantities[k])
                    .append(",\"quantity\":").append(promoIdsAndQuantities[k + 1]).append("}");
        }
        return json.append("]").toString();
    }

    @Test
    void theBirthdayCanUpsizesBuyThreeFreeOneUpToTwoPerReceiptAllMonth() throws Exception {
        pointsAccumulate();
        toteBagNotRunning();
        Long birthdayMember = member("Ahmad Faiz", LocalDate.now(OUTLET).withYear(1990).withDayOfMonth(1));

        // 6 bought earn 2 free: both may be birthday cans...
        sell(birthdayMember, bought(6, 25, 2), "174.00").andExpect(status().isCreated());
        // ...but they share the allowance with Buy 3 Free 1.
        sell(birthdayMember, bought(6, 21, 1, 25, 2), "174.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Buy 3 Free 1 Promo / Birthday Reward"));
        // 9 bought: 1 baby can and 2 birthday cans; never more than 2 birthday cans a receipt.
        sell(birthdayMember, bought(9, 21, 1, 25, 2), "261.00").andExpect(status().isCreated());
        sell(birthdayMember, bought(9, 25, 3), "261.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Birthday Reward: max 2 per receipt"));

        sell(memberId, bought(3, 25, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Birthday Reward: not birth month")));
    }

    @Test
    void aToteBagIsFreeForEveryRm150AndAMemberMustBeGivenIt() throws Exception {
        pointsAccumulate();
        // 11 × 29.00 = 319.00: two tote bags, not three...
        sell(memberId, bought(11, 28, 2, 21, 3), "319.00").andExpect(status().isCreated());
        sell(memberId, bought(11, 28, 3, 21, 3), "319.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Spend RM150 Free Tote Bag"));
        // ...and not fewer.
        sell(memberId, bought(11, 28, 1, 21, 3), "319.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 1 more Spend RM150 Free Tote Bag"));
        // Below RM150 there is none to give; without a member, none at all.
        sell(memberId, bought(5, 21, 1), "145.00").andExpect(status().isCreated());
        sell(null, bought(11), "319.00").andExpect(status().isCreated());
    }

    @Test
    void theSeasonalFlavourGivesOneMoreFreeBabyCanForEveryThreeItemsWithASeasonalFlavourAmongThem() throws Exception {
        pointsAccumulate();
        // 2 Tin Caramel + 1 PC Curry = 3 bought: 1 Buy 3 baby can, and 1 for the seasonal flavour.
        sell(memberId, "[{\"itemId\":4,\"quantity\":2},{\"itemId\":30,\"quantity\":1},"
                + "{\"itemId\":21,\"quantity\":1},{\"itemId\":29,\"quantity\":1}]", "72.00")
                .andExpect(status().isCreated());
        // 3 PC Curry: the same, 2 free.
        sell(memberId, curry(3, 21, 1, 29, 1), "42.00").andExpect(status().isCreated());
        // 6 PC Curry: 2 + 2.
        sell(memberId, curry(6, 21, 2, 29, 2), "84.00").andExpect(status().isCreated());
        // 2 PC Curry alone are not 3 items: nothing free.
        sell(memberId, curry(2), "28.00").andExpect(status().isCreated());
        sell(memberId, curry(2, 29, 1), "28.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Seasonal Flavour Promo"));
        // 5 Tin Caramel + 1 PC Curry = 6: 2 Buy 3 baby cans, but only 1 seasonal one.
        String fiveAndOne = "[{\"itemId\":4,\"quantity\":5},{\"itemId\":30,\"quantity\":1},"
                + "{\"itemId\":21,\"quantity\":2},{\"itemId\":29,\"quantity\":%d}]";
        sell(memberId, fiveAndOne.formatted(1), "159.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(startsWith("Select 1 Spend RM150 Free Tote Bag")));
        toteBagNotRunning();
        sell(memberId, fiveAndOne.formatted(1), "159.00").andExpect(status().isCreated());
        sell(memberId, fiveAndOne.formatted(2), "159.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Seasonal Flavour Promo"));
        // No seasonal flavour among them: no seasonal baby can.
        sell(memberId, bought(3, 21, 1, 29, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Remove 1 Seasonal Flavour Promo")));
    }

    @Test
    void aSeasonalFlavourIsAnOrdinaryItemAfterItsLastDay() throws Exception {
        pointsAccumulate();
        jdbc.update("UPDATE item SET seasonal_until = ? WHERE id = 30", Date.valueOf(LocalDate.now(OUTLET).minusDays(1)));
        // 3 PC Curry earn only the Buy 3 baby can now.
        sell(memberId, curry(3, 21, 1), "42.00").andExpect(status().isCreated());
        sell(memberId, curry(3, 21, 1, 29, 1), "42.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Remove 1 Seasonal Flavour Promo"));

        // And before its first day.
        jdbc.update("UPDATE item SET seasonal_from = ?, seasonal_until = ? WHERE id = 30",
                Date.valueOf(LocalDate.now(OUTLET).plusDays(1)), Date.valueOf(LocalDate.now(OUTLET).plusDays(30)));
        sell(memberId, curry(3, 21, 1), "42.00").andExpect(status().isCreated());
    }

    /** Lines: n × PC Curry (14.00, the seasonal flavour), then promotional items as {id: quantity}. */
    private static String curry(int cans, int... promoIdsAndQuantities) {
        return items(30, cans, promoIdsAndQuantities);
    }

    // ---- Quote ----

    @Test
    void theQuoteListsEveryProblemAndPreviewsThePointsWithoutSaving() throws Exception {
        quote(memberId, "[{\"itemId\":1,\"quantity\":1},{\"itemId\":21,\"quantity\":1},{\"itemId\":26,\"quantity\":1},{\"itemId\":23,\"quantity\":1}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(16.0))
                .andExpect(jsonPath("$.problems", hasSize(3)))
                .andExpect(jsonPath("$.problems", hasItem(containsString("Buy 3 Free 1 Promo"))))
                .andExpect(jsonPath("$.problems", hasItem("Spend RM50 Free Bag (S): min RM50.00")))
                .andExpect(jsonPath("$.problems", hasItem(containsString("Not enough points"))))
                .andExpect(jsonPath("$.loyalty.earned").value(1))
                .andExpect(jsonPath("$.loyalty.redeemed").value(7));

        quote(null, lines(4, 1, null, 0))
                .andExpect(jsonPath("$.problems").isEmpty())
                .andExpect(jsonPath("$.loyalty").doesNotExist());
        assertThat(count("sale_order")).isZero();
    }

    @Test
    void categorySalesCountFreeItemsAtTheirValueAndSamplesAsSamples() throws Exception {
        pointsAccumulate();
        jdbc.update("INSERT INTO item (id, category_id, name, price, sort_order) OVERRIDING SYSTEM VALUE "
                + "VALUES (40, 4, 'SPL Cheese', 0.00, 40)");
        sell(memberId, bought(3, 21, 1, 40, 2), "87.00").andExpect(status().isCreated());
        String today = LocalDate.now(OUTLET).toString();

        mockMvc.perform(get("/api/v1/reports/category-sales").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.categories[?(@.categoryName == 'Promo')].quantity").value(contains(3)))
                .andExpect(jsonPath("$.categories[?(@.categoryName == 'Promo')].amount").value(contains(6.5)))
                .andExpect(jsonPath("$..items[?(@.itemName == 'SPL Cheese')].prices[0].label").value(contains("Sample")))
                .andExpect(jsonPath("$..items[?(@.itemName == 'B3 F1 BC Caramel')].prices[0].label").value(contains("Unit Value")))
                .andExpect(jsonPath("$..items[?(@.itemName == 'B3 F1 BC Caramel')].prices[0].amount").value(contains(6.5)))
                .andExpect(jsonPath("$..items[?(@.itemName == 'Tin Caramel')].prices[0].label").value(contains("Unit Price")));
    }

    @Test
    void theLoyaltyPointsReportShowsPointsIssuedAndRedeemedAndRefunds() throws Exception {
        freeGiftsNotRunning();
        // 87.00 earns 8 and the Member FOC can costs 7.
        String body = sell(memberId, lines(4, 3, 23, 1), "87.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String today = LocalDate.now(OUTLET).toString();
        mockMvc.perform(get("/api/v1/reports/loyalty-points").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.issued").value(8))
                .andExpect(jsonPath("$.redeemed").value(7))
                .andExpect(jsonPath("$.issuedReversed").value(0))
                .andExpect(jsonPath("$.redemptions[0].name").value("Member Redemption"))
                .andExpect(jsonPath("$.redemptions[0].count").value(1))
                .andExpect(jsonPath("$.redemptions[0].points").value(7));

        Integer orderId = JsonPath.read(body, "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund").with(manager())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Damaged\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/reports/loyalty-points").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.issuedReversed").value(8))
                .andExpect(jsonPath("$.redeemedReturned").value(7));
    }

    @Test
    void theDiscountsReportListsPromotionsWithTheDiscounts() throws Exception {
        pointsAccumulate();
        sell(memberId, lines(4, 3, 21, 1), "87.00").andExpect(status().isCreated());
        String today = LocalDate.now(OUTLET).toString();

        mockMvc.perform(get("/api/v1/reports/discounts").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.discounts[*].name").value(contains("Buy 3 Free 1 Promo")))
                .andExpect(jsonPath("$.discounts[0].amount").value(6.5));
        mockMvc.perform(get("/api/v1/reports/sales-summary").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.grossSales").value(93.5))
                .andExpect(jsonPath("$.discounts").value(6.5))
                .andExpect(jsonPath("$.netSales").value(87.0));
    }

    // ---- helpers ----

    /** {@code [{itemId, quantity}, {promoItemId, promoQuantity}]}; the second line is omitted when null. */
    private static String lines(long itemId, int quantity, Integer promoItemId, int promoQuantity) {
        String first = "{\"itemId\":" + itemId + ",\"quantity\":" + quantity + "}";
        return promoItemId == null ? "[" + first + "]"
                : "[" + first + ",{\"itemId\":" + promoItemId + ",\"quantity\":" + promoQuantity + "}]";
    }

    private ResultActions sell(Long customerId, String lines, String amount) throws Exception {
        return checkout(customerId, "PAY", lines, "[]", amount);
    }

    private ResultActions place(Long customerId, String lines) throws Exception {
        return checkout(customerId, "PLACE_ORDER", lines, "[]", null);
    }

    private ResultActions checkout(Long customerId, String mode, String lines, String discountIds, String amount)
            throws Exception {
        String payments = amount == null ? "[]"
                : "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]";
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"%s","customerId":%s,"lines":%s,"discountIds":%s,"payments":%s}"""
                .formatted(UUID.randomUUID(), mode, customerId, lines, discountIds, payments)));
    }

    private ResultActions quote(Long customerId, String lines) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/quote").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","customerId":%s,"lines":%s,"payments":[]}"""
                .formatted(UUID.randomUUID(), customerId, lines)));
    }

    private ResultActions perks(Long customerId) throws Exception {
        return mockMvc.perform(get("/api/v1/customers/" + customerId + "/perks").with(cashier()));
    }

    /** For tests of other rules: without Buy 3 Free 1 (or the birthday can) running, no free item is due. */
    private void freeGiftsNotRunning() {
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD')");
    }

    /** For tests of other rules: without the tote bag running, a sale of RM150 or more needs none. */
    private void toteBagNotRunning() {
        jdbc.update("UPDATE promotion SET active = FALSE WHERE name = 'Spend RM150 Free Tote Bag'");
    }

    /** For tests of other rules: without a Member Redemption running, points simply accumulate. */
    private void pointsAccumulate() {
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type = 'POINTS_REDEMPTION'");
    }

    private void setBalance(int points) {
        jdbc.update("""
                INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (?, ?, now())
                ON CONFLICT (customer_id) DO UPDATE SET points_balance = EXCLUDED.points_balance
                """, memberId, points);
    }

    /** A member who signed up just now. */
    private Long member(String fullName, LocalDate dateOfBirth) {
        return member(fullName, dateOfBirth, Instant.now());
    }

    private Long member(String fullName, LocalDate dateOfBirth, Instant signedUp) {
        String[] names = fullName.split(" ", 2);
        return jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, names[0], names[1], Date.valueOf(dateOfBirth),
                Timestamp.from(signedUp), Timestamp.from(signedUp));
    }

    private int balance() {
        return jdbc.queryForList("SELECT points_balance FROM loyalty_account WHERE customer_id = ?", Integer.class, memberId)
                .stream().findFirst().orElse(0);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private RequestPostProcessor cashierAt(long outletId) {
        return jwt().jwt(token -> token.claim("outlet", outletId).subject(cashierId.toString()));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
