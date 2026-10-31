package com.counterline.pos.orders;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules at their edges. Points: earning by the ringgit, a refund taking back points already spent (the balance goes
 * negative and later purchases pay it off), competing redemptions. Placed orders: judged as of when they were placed
 * (dates, birth month, the registration reward's 24 hours, the catalogue's flags) while the member's points and the
 * once-only rewards are looked at again at payment. Caps and stacking. Items (promotions-fixture.sql): 1 Pouch Cheese
 * 16.00, 4 Tin Caramel 29.00, 21 Buy 3 Free 1 can, 23 Member FOC (7 points), 24 Registration Reward, 25 Birthday
 * can, 26/27 free recycle bags, 28 tote bag (RM150), 29 seasonal free can, 30 PC Curry (seasonal flavour 14.00), 31
 * Google Review Reward.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class PromotionsLoyaltyRulesIntegrationTest {

    private static final ZoneId OUTLET = ZoneId.of("Asia/Kuala_Lumpur");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    private Long cashierId;
    private Long managerId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Rules Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Rules Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        // Born six months from now: never this month.
        memberId = member("Nur Aisyah", LocalDate.now(OUTLET).plusMonths(6).withYear(1995).withDayOfMonth(15), Instant.now());
    }

    // ---- Points: earning, refunds after spending, debt ----

    @Test
    void aPointIsEarnedForEveryFullRm10() throws Exception {
        freeGiftsNotRunning();
        jdbc.update("UPDATE item SET price = 9.99 WHERE id = 1");
        sell(memberId, lines(1, 1), "9.99").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.earned").value(0));
        jdbc.update("UPDATE item SET price = 10.00 WHERE id = 1");
        sell(memberId, lines(1, 1), "10.00").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.earned").value(1));
        jdbc.update("UPDATE item SET price = 19.99 WHERE id = 1");
        sell(memberId, lines(1, 1), "19.99").andExpect(jsonPath("$.loyalty.earned").value(1));
        assertThat(balance()).isEqualTo(2);
    }

    @Test
    void aRefundTakesBackPointsAlreadySpentSoTheBalanceGoesNegativeAndLaterPurchasesPayItOff() throws Exception {
        freeGiftsNotRunning();
        // 61.00 earns 6. Spent on the next sale: 6 + 1 earned = 7, redeemed for the Member FOC item.
        long first = idOf(sell(memberId, lines(1, 2, 4, 1), "61.00").andExpect(status().isCreated()));
        sell(memberId, lines(1, 1, 23, 1), "16.00").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.after").value(0));
        assertThat(balance()).isZero();

        as(manager(), post("/api/v1/orders/" + first + "/refund"), "{\"reason\":\"Wrong flavour\"}").andExpect(status().isOk());

        // The whole 6 is taken back: the member owes 6 points.
        assertThat(balance()).isEqualTo(-6);
        assertThat(jdbc.queryForList("SELECT points FROM loyalty_entry WHERE type = 'REVERSE_EARN'", Integer.class)).containsExactly(-6);
        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null).andExpect(jsonPath("$.points").value(-6));
        as(admin(), get("/api/v1/dashboard/customers/" + memberId + "/points"), null).andExpect(jsonPath("$.balance").value(-6));

        // The next purchases earn it back first: 32.00 earns 3.
        quote(memberId, lines(1, 2)).andExpect(jsonPath("$.loyalty.before").value(-6)).andExpect(jsonPath("$.loyalty.after").value(-3));
        sell(memberId, lines(1, 2), "32.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.loyalty.before").value(-6)).andExpect(jsonPath("$.loyalty.after").value(-3));
        sell(memberId, lines(1, 2), "32.00").andExpect(jsonPath("$.loyalty.after").value(0));
        assertThat(balance()).isZero();
        sell(memberId, lines(1, 2), "32.00").andExpect(jsonPath("$.loyalty.after").value(3));
    }

    @Test
    void aMemberInDebtCannotRedeemAndAnAdjustmentCannotDeepenIt() throws Exception {
        freeGiftsNotRunning();
        setBalance(-6);

        // 87.00 earns 8: -6 + 8 = 2 available, 7 needed. Points still have to be there to be spent.
        sell(memberId, lines(4, 3, 23, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Not enough points (7 needed, 2 available)"));

        // Manual adjustments keep their rule: nothing is taken away from a member in debt, points can be added.
        as(admin(), post("/api/v1/dashboard/customers/" + memberId + "/points"), "{\"points\":-1,\"reason\":\"Try\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("owes 6 points")));
        as(admin(), post("/api/v1/dashboard/customers/" + memberId + "/points"), "{\"points\":3,\"reason\":\"Goodwill\"}").andExpect(status().isOk());
        assertThat(balance()).isEqualTo(-3);
        setBalance(5);
        as(admin(), post("/api/v1/dashboard/customers/" + memberId + "/points"), "{\"points\":-6,\"reason\":\"Too many\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("has only 5 points")));
        assertThat(balance()).isEqualTo(5);
    }

    @Test
    void aSaleCanRedeemPointsAndTakeAFreeItemEarnedByWhatIsBought() throws Exception {
        // 3 Tin Caramel (87.00 earns 8): one Buy 3 Free 1 can and one Member FOC item together.
        sell(memberId, lines(4, 3), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Select 1 Buy 3 Free 1 Promo")));
        sell(memberId, lines(4, 3, 21, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Select 1 Member FOC"));
        sell(memberId, lines(4, 3, 21, 1, 23, 1), "87.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(103.5)).andExpect(jsonPath("$.discountTotal").value(16.5))
                .andExpect(jsonPath("$.total").value(87.0))
                .andExpect(jsonPath("$.loyalty.redeemed").value(7)).andExpect(jsonPath("$.loyalty.after").value(1));
    }

    // ---- Placed orders and members who become inactive ----

    @Test
    void anInactiveMembersPlacedOrderCanStillBePaidAndRefundedWithItsPoints() throws Exception {
        setBalance(7);
        long order = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        jdbc.update("UPDATE customer SET deactivated_at = now() WHERE id = ?", memberId);

        pay(order, "16.00").andExpect(status().isOk()).andExpect(jsonPath("$.loyalty.redeemed").value(7)).andExpect(jsonPath("$.loyalty.after").value(1));
        assertThat(balance()).isEqualTo(1);
        as(manager(), post("/api/v1/orders/" + order + "/refund"), "{\"reason\":\"Returned\"}").andExpect(status().isOk());
        assertThat(balance()).isEqualTo(7);
    }

    @Test
    void editingAPlacedOrderChangesWhatItRedeemsAndPayingChecksThePointsAgain() throws Exception {
        setBalance(14);
        long order = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        assertThat(redeemedPoints(order)).isEqualTo(7L);

        // Paying through the edit with no redemption leaves 14 + 1 points: two must be redeemed.
        editPay(order, lines(1, 1), "16.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Select 2 Member FOC"));
        // Redeeming two instead: the order now spends 14.
        edit(order, lines(1, 1, 23, 2)).andExpect(status().isOk());
        assertThat(redeemedPoints(order)).isEqualTo(14L);
        pay(order, "16.00").andExpect(status().isOk()).andExpect(jsonPath("$.loyalty.redeemed").value(14)).andExpect(jsonPath("$.loyalty.after").value(1));
        assertThat(balance()).isEqualTo(1);
    }

    // ---- A placed order is judged as of when it was placed ----

    @Test
    void promotionDatesAreInclusiveAndAreReadAtPlacementNotAtPayment() throws Exception {
        Long reviewer = member("Lim Wei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        Long other = member("Tan Mei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        // Three days, some days ago: all of it is over by the time the orders below are paid.
        LocalDate firstDay = LocalDate.now(OUTLET).minusDays(10);
        LocalDate lastDay = firstDay.plusDays(2);
        jdbc.update("UPDATE promotion SET starts_on = ?, ends_on = ? WHERE name = 'Google Review Reward'", Date.valueOf(firstDay), Date.valueOf(lastDay));
        String beforeFirstDay = firstDay.atStartOfDay(OUTLET).toInstant().minusSeconds(1).toString(); // 23:59:59 the day before
        String startOfFirstDay = firstDay.atStartOfDay(OUTLET).toInstant().toString();
        String startOfDayAfterLast = lastDay.plusDays(1).atStartOfDay(OUTLET).toInstant().toString();
        String endOfLastDay = lastDay.plusDays(1).atStartOfDay(OUTLET).toInstant().minusSeconds(1).toString();

        long order = idOf(place(reviewer, lines(1, 1, 31, 1)).andExpect(status().isCreated()));
        judgedAt(order, beforeFirstDay);
        pay(order, "16.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("Google Review Reward: " + firstDay + " to " + lastDay)));
        judgedAt(order, startOfDayAfterLast);
        pay(order, "16.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("Google Review Reward")));
        // The last second of the last day, though the promotion ended long before it is paid.
        judgedAt(order, endOfLastDay);
        pay(order, "16.00").andExpect(status().isOk());

        long first = idOf(place(other, lines(1, 1, 31, 1)).andExpect(status().isCreated()));
        judgedAt(first, startOfFirstDay); // the first second of the first day
        pay(first, "16.00").andExpect(status().isOk());

        // A sale made now, after the promotion, is refused.
        Long newcomer = member("Goh Ann", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        sell(newcomer, lines(1, 1, 31, 1), "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Google Review Reward")));
    }

    @Test
    void theBirthMonthIsTheOutletsMonthAtPlacementIncludingNoBirthDateAndTwentyNinthOfFebruary() throws Exception {
        String birthday = "[{\"itemId\":1,\"quantity\":3},{\"itemId\":25,\"quantity\":1}]";
        Long november = member("Nov Born", LocalDate.of(1990, 11, 15), Instant.now());
        Long october = member("Oct Born", LocalDate.of(1990, 10, 20), Instant.now());
        Long unknown = memberWithoutBirthDate("No Date");
        Long leap = member("Leap Born", LocalDate.of(1992, 2, 29), Instant.now());

        // Born in November: 31 October 23:59:59 is October, 1 November 00:00:00 is November (outlet time).
        long order = idOf(place(november, birthday).andExpect(status().isCreated()));
        judgedAt(order, "2026-10-31T15:59:59Z");
        pay(order, "48.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("Birthday Reward: not birth month")));
        judgedAt(order, "2026-10-31T16:00:00Z");
        pay(order, "48.00").andExpect(status().isOk());

        // Born in October: the last second of October counts, the first of November does not.
        order = idOf(place(october, birthday).andExpect(status().isCreated()));
        judgedAt(order, "2026-10-31T16:00:00Z");
        pay(order, "48.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("not birth month")));
        judgedAt(order, "2026-10-31T15:59:59Z");
        pay(order, "48.00").andExpect(status().isOk());

        order = idOf(place(unknown, birthday).andExpect(status().isCreated()));
        pay(order, "48.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("Birthday Reward: no birth date")));

        // Born on 29 February: all of February in any year, and not March.
        order = idOf(place(leap, birthday).andExpect(status().isCreated()));
        judgedAt(order, "2026-02-28T16:00:00Z"); // 1 March
        pay(order, "48.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("not birth month")));
        judgedAt(order, "2026-02-28T15:59:59Z"); // 28 February
        pay(order, "48.00").andExpect(status().isOk());
    }

    @Test
    void theRegistrationRewardLastsExactly24HoursFromSigningUpAndAPlacedOrderKeepsItsPlace() throws Exception {
        Instant signedUp = Instant.parse("2026-10-12T02:00:00Z");
        Long exact = member("Exact Hours", LocalDate.of(1990, 1, 1), signedUp);
        long order = idOf(place(exact, lines(1, 1, 24, 1)).andExpect(status().isCreated()));

        judgedAt(order, signedUp.plus(Duration.ofHours(24)).toString());
        pay(order, "16.00").andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Registration Reward Promo: expired"));
        judgedAt(order, signedUp.plus(Duration.ofHours(24)).minusMillis(1).toString());
        pay(order, "16.00").andExpect(status().isOk());

        // Placed inside the 24 hours and paid after them: the order was eligible when it was placed.
        Instant longAgo = Instant.now().minus(Duration.ofHours(25));
        Long newMember = member("Fresh Member", LocalDate.of(1990, 1, 1), longAgo);
        long placed = idOf(place(newMember, lines(1, 1, 24, 1)).andExpect(status().isCreated()));
        judgedAt(placed, longAgo.plus(Duration.ofHours(23)).toString());
        pay(placed, "16.00").andExpect(status().isOk());
        // A sale made now is too late for them.
        sell(newMember, lines(1, 1, 24, 1), "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Registration Reward Promo")));
    }

    @Test
    void theFoldersAndSeasonalFlagsAreThoseOfTheDayTheOrderWasPlaced() throws Exception {
        pointsAccumulate();
        // Three Pouch items and the Buy 3 Free 1 can.
        long counted = idOf(place(memberId, lines(1, 3, 21, 1)).andExpect(status().isCreated()));
        // Three PC Curry (seasonal), the Buy 3 Free 1 can and the seasonal flavour's can.
        long seasonal = idOf(place(memberId, lines(30, 3, 21, 1, 29, 1)).andExpect(status().isCreated()));

        // Both are changed in the catalogue after the orders were placed.
        jdbc.update("UPDATE category SET counts_for_promotions = FALSE WHERE id IN (1, 3)");
        jdbc.update("UPDATE item SET seasonal = FALSE WHERE id = 30");

        pay(counted, "48.00").andExpect(status().isOk());
        pay(seasonal, "42.00").andExpect(status().isOk());
        // A sale made now sees the catalogue as it is: the Pouch items no longer earn a free can.
        sell(memberId, lines(1, 3, 21, 1), "48.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Remove 1")));
    }

    @Test
    void payingAPlacedOrderStillChecksThePointsAndTheOnceOnlyRewardsAsTheyAreNow() throws Exception {
        long firstReward = idOf(place(memberId, lines(1, 1, 24, 1)).andExpect(status().isCreated()));
        long secondReward = idOf(place(memberId, lines(1, 1, 24, 1)).andExpect(status().isCreated()));
        pay(firstReward, "16.00").andExpect(status().isOk());
        pay(secondReward, "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Registration Reward Promo: already used"));

        setBalance(7);
        long first = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        long second = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        pay(first, "16.00").andExpect(status().isOk());
        pay(second, "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Not enough points (7 needed, 2 available)"));
    }

    // ---- Limits and stacking ----

    @Test
    void oneFreeRecycleBagAtMostAndTheBirthdayCanUpToTwoPerReceipt() throws Exception {
        freeGiftsNotRunning();
        pointsAccumulate();
        // 87.00 allows both bags (RM50 and RM80), but they share one "free recycle bag" allowance.
        sell(memberId, lines(4, 3, 26, 1, 27, 1), "87.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("1 free recycle bag only")));
        sell(memberId, lines(4, 3, 26, 1), "87.00").andExpect(status().isCreated());

        // The birthday can: 9 Pouch items earn 3 free, the promotion allows 2 per receipt.
        jdbc.update("UPDATE promotion SET active = TRUE WHERE type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD')");
        Long born = member("This Month", LocalDate.now(OUTLET).withYear(2000), Instant.now());
        sell(born, lines(1, 9, 25, 3), "144.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Birthday Reward: max 2 per receipt")));
        sell(born, lines(1, 9, 25, 2, 21, 1), "144.00").andExpect(status().isCreated());
    }

    @Test
    void aSpendGiftStillWorksWithADiscountAndIsJudgedOnTheDiscountedTotal() throws Exception {
        freeGiftsNotRunning();
        pointsAccumulate();
        long tenPercent = discount("Spend Ten", 10);
        long twentyPercent = discount("Spend Twenty", 20);

        // 6 Tin Caramel 174.00 less 10% is 156.60: over RM150, so the tote bag must be given, and may be with a discount.
        sell(memberId, lines(4, 6), "[" + tenPercent + "]", "156.60").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Spend RM150 Free Tote Bag")));
        sell(memberId, lines(4, 6, 28, 1), "[" + tenPercent + "]", "156.60").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(156.6)).andExpect(jsonPath("$.orderDiscounts[0].name").value("Spend Ten"));
        // Less 20% it is 139.20: under RM150, no tote bag due or allowed.
        sell(memberId, lines(4, 6, 28, 1), "[" + twentyPercent + "]", "139.20").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Spend RM150 Free Tote Bag: min RM150")));
        sell(memberId, lines(4, 6), "[" + twentyPercent + "]", "139.20").andExpect(status().isCreated());
    }

    // ---- At the same time ----

    @Test
    void twoOrdersSpendingTheSamePointsAtOnceLetExactlyOneIn() throws Exception {
        setBalance(7);
        long first = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        long second = idOf(place(memberId, lines(1, 1, 23, 1)).andExpect(status().isCreated()));

        List<MockHttpServletResponse> answers = raceAll(
                () -> pay(first, "16.00").andReturn().getResponse(), () -> pay(second, "16.00").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
        assertThat(answers.stream().filter(answer -> answer.getStatus() == 422).findFirst().orElseThrow().getContentAsString()).contains("Not enough points");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_entry WHERE type = 'REDEEM'", Long.class)).isEqualTo(1L);
        assertThat(balance()).isEqualTo(1);
    }

    @Test
    void aOnceOnlyRewardTakenOnTwoTillsAtOnceIsGivenOnce() throws Exception {
        Long reviewer = member("Lim Wei", LocalDate.of(1990, 1, 1), Instant.now().minus(Duration.ofDays(400)));
        for (int[] reward : new int[][]{{24, 0}, {31, 1}}) {
            Long who = reward[1] == 0 ? memberId : reviewer;
            long first = idOf(place(who, lines(1, 1, reward[0], 1)).andExpect(status().isCreated()));
            long second = idOf(place(who, lines(1, 1, reward[0], 1)).andExpect(status().isCreated()));

            List<MockHttpServletResponse> answers = raceAll(
                    () -> pay(first, "16.00").andReturn().getResponse(), () -> pay(second, "16.00").andReturn().getResponse());

            assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
            assertThat(answers.stream().filter(answer -> answer.getStatus() == 422).findFirst().orElseThrow().getContentAsString())
                    .contains("already used");
        }
    }

    @Test
    void aPaymentAndARefundForOneMemberAtOnceKeepTheBalanceEqualToTheirEntries() throws Exception {
        freeGiftsNotRunning();
        long paidOrder = idOf(sell(memberId, lines(1, 1), "16.00").andExpect(status().isCreated()));
        long placed = idOf(place(memberId, lines(1, 1)).andExpect(status().isCreated()));

        List<MockHttpServletResponse> answers = raceAll(
                () -> as(manager(), post("/api/v1/orders/" + paidOrder + "/refund"), "{\"reason\":\"Both\"}").andReturn().getResponse(),
                () -> pay(placed, "16.00").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
        assertThat(balance()).isEqualTo(jdbc.queryForObject("SELECT coalesce(sum(points), 0) FROM loyalty_entry WHERE customer_id = ?", Integer.class, memberId));
        assertThat(balance()).isEqualTo(1);
    }

    // ---- helpers ----

    private static String lines(int... pairs) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"itemId\":").append(pairs[i]).append(",\"quantity\":").append(pairs[i + 1]).append('}');
        }
        return json.append(']').toString();
    }

    private ResultActions sell(Long customer, String lines, String amount) throws Exception {
        return sell(customer, lines, "[]", amount);
    }

    private ResultActions sell(Long customer, String lines, String discountIds, String amount) throws Exception {
        return as(cashier(), post("/api/v1/orders"), body(customer, "PAY", lines, discountIds,
                "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]"));
    }

    private ResultActions place(Long customer, String lines) throws Exception {
        return as(cashier(), post("/api/v1/orders"), body(customer, "PLACE_ORDER", lines, "[]", "[]"));
    }

    private ResultActions edit(long order, String lines) throws Exception {
        return as(cashier(), put("/api/v1/orders/" + order), body(memberId, "PLACE_ORDER", lines, "[]", "[]"));
    }

    private ResultActions editPay(long order, String lines, String amount) throws Exception {
        return as(cashier(), put("/api/v1/orders/" + order), body(memberId, "PAY", lines, "[]",
                "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]"));
    }

    private ResultActions pay(long order, String amount) throws Exception {
        return as(cashier(), post("/api/v1/orders/" + order + "/payments"), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}"""
                .formatted(UUID.randomUUID(), amount));
    }

    private ResultActions quote(Long customer, String lines) throws Exception {
        return as(cashier(), post("/api/v1/orders/quote"), body(customer, "PAY", lines, "[]", "[]"));
    }

    private static String body(Long customer, String mode, String lines, String discountIds, String payments) {
        return "{\"clientRequestId\":\"%s\",\"mode\":\"%s\",\"customerId\":%s,\"lines\":%s,\"discountIds\":%s,\"payments\":%s}"
                .formatted(UUID.randomUUID(), mode, customer, lines, discountIds, payments);
    }

    /** The instant the order's time rules are read at: when it was placed, unless a test says otherwise. */
    private void judgedAt(long order, String instant) {
        jdbc.update("UPDATE sale_order SET eligibility_at = ? WHERE id = ?", Timestamp.from(Instant.parse(instant)), order);
    }

    private long redeemedPoints(long order) {
        return jdbc.queryForObject("SELECT coalesce(sum(points), 0) FROM sale_order_promotion WHERE order_id = ?", Long.class, order);
    }

    private long discount(String name, int percent) {
        return jdbc.queryForObject("""
                INSERT INTO discount (name, kind, value, scope, sort_order) VALUES (?, 'PERCENT', ?, 'ORDER', 90)
                ON CONFLICT (name) DO UPDATE SET active = TRUE RETURNING id""", Long.class, name, percent);
    }

    private void freeGiftsNotRunning() {
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD')");
    }

    private void pointsAccumulate() {
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type = 'POINTS_REDEMPTION'");
    }

    private void setBalance(int points) {
        jdbc.update("""
                INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (?, ?, now())
                ON CONFLICT (customer_id) DO UPDATE SET points_balance = EXCLUDED.points_balance""", memberId, points);
    }

    private int balance() {
        return jdbc.queryForList("SELECT points_balance FROM loyalty_account WHERE customer_id = ?", Integer.class, memberId)
                .stream().findFirst().orElse(0);
    }

    private Long member(String fullName, LocalDate dateOfBirth, Instant signedUp) {
        String[] names = fullName.split(" ", 2);
        return jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?) RETURNING id""", Long.class, names[0], names[1], Date.valueOf(dateOfBirth),
                Timestamp.from(signedUp), Timestamp.from(signedUp));
    }

    private Long memberWithoutBirthDate(String fullName) {
        String[] names = fullName.split(" ", 2);
        return jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, created_at, updated_at) VALUES (?, ?, now(), now()) RETURNING id""",
                Long.class, names[0], names[1]);
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    @SafeVarargs
    private List<MockHttpServletResponse> raceAll(Callable<MockHttpServletResponse>... calls) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
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

    private ResultActions as(RequestPostProcessor who, MockHttpServletRequestBuilder request, String json) throws Exception {
        request.with(who);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
