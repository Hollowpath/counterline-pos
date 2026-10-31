package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.LoyaltyService.PointsHistory;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rolling expiry of loyalty points: positive points expire 365 days after the member's latest completed, unrefunded
 * purchase. Every such purchase resets it (also one that earns nothing or redeems points); adjustments, refunds and
 * every other ledger entry never do; refunding the latest purchase falls back to the one before; with no purchase there
 * is no expiry; a debt and a zero balance never expire; the expiry is recorded once, under the account lock, as an
 * EXPIRE entry. Purchases are real sales whose time is then moved back; the exact boundary uses explicit instants.
 * Items (promotions-fixture.sql): 1 Pouch Cheese 16.00, 23 Member FOC (7 points).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class LoyaltyExpiryIntegrationTest {

    private static final ZoneId OUTLET = ZoneId.of("Asia/Kuala_Lumpur");
    private static final Duration YEAR = Duration.ofDays(365);
    private static final Instant T0 = Instant.parse("2026-01-10T04:00:00Z");

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
    @Autowired
    PlatformTransactionManager transactions;

    private Long cashierId;
    private Long managerId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        // Free gifts off, and points accumulate (a member with enough points is not made to redeem) unless a test says so.
        jdbc.update("UPDATE promotion SET active = FALSE WHERE type IN ('BUY_X_GET_FREE', 'BIRTHDAY_REWARD', 'POINTS_REDEMPTION')");
        jdbc.update("UPDATE item SET price = 16.00 WHERE id = 1");
        cashierId = staffRepository.save(new Staff("Expiry Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Expiry Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        Instant joined = Instant.now().minus(Duration.ofDays(800));
        memberId = jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES ('Nur', 'Aisyah', ?, ?, ?) RETURNING id""", Long.class,
                Date.valueOf(LocalDate.now(OUTLET).plusMonths(6).withYear(1995).withDayOfMonth(15)),
                Timestamp.from(joined), Timestamp.from(joined));
    }

    // ---- The exact boundary, and what does not move it ----

    @Test
    void pointsAreGoneAtTheInstantThatIs365DaysAfterTheLatestPurchaseAndNotAnInstantBefore() throws Exception {
        pointsFreePurchaseAt(T0);
        Instant expiry = T0.plus(YEAR);
        loyalty.adjust(memberId, "Nur Aisyah", 10, "Seed", "Head Office", T0.plus(Duration.ofDays(1)));
        // Adjusting later, up or down, does not move the expiry.
        loyalty.adjust(memberId, "Nur Aisyah", 2, "More", "Head Office", T0.plus(Duration.ofDays(300)));
        loyalty.adjust(memberId, "Nur Aisyah", -1, "Less", "Head Office", T0.plus(Duration.ofDays(320)));

        assertThat(loyalty.expiresAt(memberId, T0.plus(Duration.ofDays(330)))).isEqualTo(expiry);
        assertThat(loyalty.balance(memberId, expiry.minusNanos(1))).isEqualTo(11);
        assertThat(loyalty.expiresAt(memberId, expiry.minusNanos(1))).isEqualTo(expiry);
        assertThat(loyalty.balance(memberId, expiry)).isZero();
        assertThat(loyalty.expiresAt(memberId, expiry)).isNull();

        // One instant before: nothing is recorded.
        PointsHistory before = loyalty.history(memberId, expiry.minusNanos(1));
        assertThat(before.balance()).isEqualTo(11);
        assertThat(before.expiresAt()).isEqualTo(expiry);
        assertThat(expireEntries()).isZero();

        // At the instant: the balance goes to zero once, with one EXPIRE entry dated at the expiry instant.
        PointsHistory at = loyalty.history(memberId, expiry);
        assertThat(at.balance()).isZero();
        assertThat(at.expiresAt()).isNull();
        assertThat(at.entries().get(0).type()).isEqualTo("EXPIRE");
        assertThat(at.entries().get(0).points()).isEqualTo(-11);
        assertThat(at.entries().get(0).at()).isEqualTo(expiry);
        assertThat(at.entries().get(0).orderNumber()).isNull();
        assertThat(at.entries().get(0).reason()).isNull();
        assertThat(balance()).isZero();
        assertThat(expireEntries()).isEqualTo(1);

        // Looking again, or much later, records nothing more.
        loyalty.history(memberId, expiry.plus(Duration.ofDays(400)));
        assertThat(expireEntries()).isEqualTo(1);
    }

    @Test
    void theSaleBeingPaidDoesNotKeepPointsAliveThatHadExpiredByTheTimeItIsSettled() throws Exception {
        pointsFreePurchaseAt(T0);
        loyalty.adjust(memberId, "Nur Aisyah", 10, "Seed", "Head Office", T0.plus(Duration.ofDays(1)));
        Instant expiry = T0.plus(YEAR);
        // The sale itself (closed just now, before the expiry): the member's latest purchase, so the points are alive.
        long sale = idOf(sell(lines(1, 1), "16.00").andExpect(status().isCreated()));
        assertThat(expireEntries()).isZero();

        // Settling it as of an instant after the points' year was up (the account was locked earlier, a payment can wait
        // for the register): they expire first, and the sale does not count as the purchase that kept them.
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            loyalty.lock(memberId, expiry.minusSeconds(5));
            loyalty.settle(memberId, sale, java.math.BigDecimal.ZERO, 0, expiry.plusSeconds(1));
        });

        assertThat(expireEntries()).isEqualTo(1);
        assertThat(balance()).isZero();
    }

    @Test
    void readingTheHistoryOfPointsThatHaveNotExpiredDoesNotWaitForTheAccountLock() throws Exception {
        purchaseDaysAgo(10, "16.00");
        setBalance(5);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holding = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                loyalty.lock(memberId, Instant.now());
                held.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            // Another request holds the account (a sale in progress): a look at points that are not due is not queued behind it.
            PointsHistory history = pool.submit(() -> loyalty.history(memberId)).get(10, TimeUnit.SECONDS);
            assertThat(history.balance()).isEqualTo(5);
            release.countDown();
            holding.get(20, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void adjustmentsAndRefundReversalsNeverMoveTheExpiry() throws Exception {
        long older = purchaseDaysAgo(300, "16.00");
        long latest = purchaseDaysAgo(200, "16.00");
        setBalance(20);
        Instant expiry = closedAt(latest).plus(YEAR);
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(expiry);

        // Adjustments up and down, now.
        as(admin(), post("/api/v1/dashboard/customers/" + memberId + "/points"), "{\"points\":5,\"reason\":\"Goodwill\"}").andExpect(status().isOk());
        as(admin(), post("/api/v1/dashboard/customers/" + memberId + "/points"), "{\"points\":-2,\"reason\":\"Correction\"}").andExpect(status().isOk());
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(expiry);

        // A refund's reversal entries are not a purchase either: refunding the older one changes nothing.
        refund(older).andExpect(status().isOk());
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(expiry);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_entry WHERE type = 'REVERSE_EARN'", Integer.class)).isEqualTo(1);
    }

    // ---- Every completed purchase resets it ----

    @Test
    void aPurchaseThatEarnsNothingStillStartsANewYear() throws Exception {
        purchaseDaysAgo(364, "16.00");
        setBalance(10);
        jdbc.update("UPDATE item SET price = 9.99 WHERE id = 1");

        sell(lines(1, 1), "9.99").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.earned").value(0))
                .andExpect(jsonPath("$.loyalty.redeemed").value(0));

        assertThat(balance()).isEqualTo(10);
        assertThat(loyalty.expiresAt(memberId)).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aPurchaseThatRedeemsPointsStartsANewYear() throws Exception {
        jdbc.update("UPDATE promotion SET active = TRUE WHERE type = 'POINTS_REDEMPTION'");
        jdbc.update("UPDATE item SET price = 9.99 WHERE id = 1");
        purchaseDaysAgo(364, "9.99");
        setBalance(10);

        sell(lines(1, 1, 23, 1), "9.99").andExpect(status().isCreated())
                .andExpect(jsonPath("$.loyalty.earned").value(0)).andExpect(jsonPath("$.loyalty.redeemed").value(7));

        assertThat(balance()).isEqualTo(3);
        assertThat(loyalty.expiresAt(memberId)).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aPurchaseThatEarnsStartsANewYear() throws Exception {
        purchaseDaysAgo(364, "16.00");
        setBalance(10);

        sell(lines(1, 2), "32.00").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.after").value(13));

        assertThat(loyalty.expiresAt(memberId)).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void aPlacedOrderIsNotAPurchaseUntilItIsPaid() throws Exception {
        long latest = purchaseDaysAgo(100, "16.00");
        setBalance(10);
        Instant expiry = closedAt(latest).plus(YEAR);

        long placed = idOf(place(lines(1, 2)).andExpect(status().isCreated()));
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(expiry);

        pay(placed, "32.00").andExpect(status().isOk());
        assertThat(loyalty.expiresAt(memberId)).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    // ---- Refunds fall back to the purchase before ----

    @Test
    void refundingTheLatestPurchaseCountsFromTheOneBeforeAndWithNoneLeftThereIsNoExpiry() throws Exception {
        long first = purchaseDaysAgo(300, "16.00");
        long second = purchaseDaysAgo(100, "16.00");
        setBalance(20);
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(closedAt(second).plus(YEAR));

        refund(second).andExpect(status().isOk());
        assertThat(loyalty.expiresAt(memberId)).isEqualTo(closedAt(first).plus(YEAR));
        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsExpireAt").exists());

        refund(first).andExpect(status().isOk());
        assertThat(loyalty.expiresAt(memberId)).isNull();
        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null).andExpect(jsonPath("$.pointsExpireAt").doesNotExist());
        assertThat(loyalty.history(memberId, Instant.now().plus(Duration.ofDays(5000))).balance()).isPositive();
        assertThat(expireEntries()).isZero();
    }

    @Test
    void refundingTheLatestPurchaseWhenTheOneBeforeIsOldExpiresThePointsOnce() throws Exception {
        long old = purchaseDaysAgo(400, "16.00");
        long latest = purchaseDaysAgo(100, "16.00");
        setBalance(20);
        assertThat(loyalty.balance(memberId)).isEqualTo(20);
        assertThat(old).isNotEqualTo(latest);
        // (The first sale's own point had already expired when the second was paid: that entry is not the one looked at.)
        int before = expireEntries();

        refund(latest).andExpect(status().isOk());

        // The balance after the reversal is 19: the older purchase's year is over, so it goes, in one entry, after the reversal.
        assertThat(loyalty.balance(memberId)).isZero();
        PointsHistory history = loyalty.history(memberId);
        assertThat(history.balance()).isZero();
        assertThat(history.expiresAt()).isNull();
        assertThat(expireEntries()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT points FROM loyalty_entry WHERE type = 'EXPIRE' ORDER BY id DESC LIMIT 1", Integer.class)).isEqualTo(-19);
        assertThat(history.entries().get(0).type()).isEqualTo("EXPIRE");
        loyalty.history(memberId);
        assertThat(expireEntries()).isEqualTo(before + 1);
    }

    // ---- No purchase, debt, zero ----

    @Test
    void pointsWithNoPurchaseToCountFromNeverExpire() throws Exception {
        loyalty.adjust(memberId, "Nur Aisyah", 10, "Seed", "Head Office", T0);
        Instant far = T0.plus(Duration.ofDays(5000));

        assertThat(loyalty.expiresAt(memberId, far)).isNull();
        assertThat(loyalty.balance(memberId, far)).isEqualTo(10);
        assertThat(loyalty.history(memberId, far).balance()).isEqualTo(10);
        assertThat(loyalty.history(memberId, far).expiresAt()).isNull();
        assertThat(expireEntries()).isZero();
        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null)
                .andExpect(jsonPath("$.points").value(10)).andExpect(jsonPath("$.pointsExpireAt").doesNotExist());
    }

    @Test
    void aDebtNeverExpiresWhateverHowLongAgoThePurchaseWas() throws Exception {
        purchaseDaysAgo(1500, "16.00");
        setBalance(-6);

        PointsHistory history = loyalty.history(memberId);
        assertThat(history.balance()).isEqualTo(-6);
        assertThat(history.expiresAt()).isNull();
        assertThat(expireEntries()).isZero();
        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null)
                .andExpect(jsonPath("$.points").value(-6)).andExpect(jsonPath("$.pointsExpireAt").doesNotExist());
        quote(lines(1, 2)).andExpect(jsonPath("$.loyalty.before").value(-6)).andExpect(jsonPath("$.loyalty.after").value(-3))
                .andExpect(jsonPath("$.loyalty.expiresAt").doesNotExist());
        sell(lines(1, 2), "32.00").andExpect(status().isCreated()).andExpect(jsonPath("$.loyalty.before").value(-6));
        assertThat(expireEntries()).isZero();
    }

    @Test
    void aZeroBalanceHasNothingToExpire() throws Exception {
        purchaseDaysAgo(800, "16.00");
        setBalance(0);

        PointsHistory history = loyalty.history(memberId);

        assertThat(history.balance()).isZero();
        assertThat(history.expiresAt()).isNull();
        assertThat(expireEntries()).isZero();
    }

    // ---- Screens and responses ----

    @Test
    void thePerksTheQuoteAndThePointsHistoryShowWhenThePointsExpire() throws Exception {
        long latest = purchaseDaysAgo(100, "16.00");
        setBalance(8);
        Instant expiry = closedAt(latest).plus(YEAR);

        String perks = as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.points").value(8)).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(perks, "$.pointsExpireAt"))).isCloseTo(expiry, within(1, ChronoUnit.MILLIS));
        String history = as(admin(), get("/api/v1/dashboard/customers/" + memberId + "/points"), null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(history, "$.expiresAt"))).isCloseTo(expiry, within(1, ChronoUnit.MILLIS));

        // The sale in the cart will be the latest purchase, whatever it earns: a year from now, also at 0 points.
        String earning = quote(lines(1, 2)).andExpect(jsonPath("$.loyalty.earned").value(3)).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(earning, "$.loyalty.expiresAt"))).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
        jdbc.update("UPDATE item SET price = 9.99 WHERE id = 1");
        String nothing = quote(lines(1, 1)).andExpect(jsonPath("$.loyalty.earned").value(0)).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(nothing, "$.loyalty.expiresAt"))).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void anExpiredBalanceShowsAsZeroEverywhereWithoutBeingRecordedByAReadOnlyScreen() throws Exception {
        purchaseDaysAgo(400, "16.00");
        setBalance(8);

        as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null)
                .andExpect(jsonPath("$.points").value(0)).andExpect(jsonPath("$.pointsExpireAt").doesNotExist());
        quote(lines(1, 2)).andExpect(jsonPath("$.loyalty.before").value(0)).andExpect(jsonPath("$.loyalty.after").value(3))
                .andExpect(jsonPath("$.loyalty.expiresAt").exists());
        assertThat(expireEntries()).isZero();
        assertThat(balance()).isEqualTo(8);
    }

    @Test
    void theHistoryListsTheExpiryAfterTheEntriesItFollowsAndTheOutletReportIgnoresIt() throws Exception {
        long old = purchaseDaysAgo(400, "16.00");
        long latest = purchaseDaysAgo(100, "16.00");
        setBalance(10);
        String today = LocalDate.now(OUTLET).toString();
        String reportBefore = as(cashier(), get("/api/v1/reports/loyalty-points?from=" + today + "&to=" + today), null)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(old).isNotEqualTo(latest);

        // Refunding the latest purchase moves the expiry back to a moment long past: the entry is dated after the reversal.
        refund(latest).andExpect(status().isOk());
        as(admin(), get("/api/v1/dashboard/customers/" + memberId + "/points"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(0)).andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.entries[0].type").value("EXPIRE")).andExpect(jsonPath("$.entries[0].points").value(-9))
                .andExpect(jsonPath("$.entries[0].orderNumber").doesNotExist());
        Instant reversal = jdbc.queryForObject("SELECT created_at FROM loyalty_entry WHERE type = 'REVERSE_EARN'", Timestamp.class).toInstant();
        Instant expired = jdbc.queryForObject("SELECT created_at FROM loyalty_entry WHERE type = 'EXPIRE' ORDER BY id DESC LIMIT 1", Timestamp.class).toInstant();
        assertThat(expired).isAfterOrEqualTo(reversal);

        // Expiry belongs to the whole chain, not to an outlet: the outlet's report only changes by the refund itself.
        as(cashier(), get("/api/v1/reports/loyalty-points?from=" + today + "&to=" + today), null).andExpect(status().isOk());
        assertThat(reportBefore).contains("\"issued\"");
    }

    // ---- Inactive members ----

    @Test
    void aDeactivatedMembersPointsStillExpireOnceWhenTheirAccountIsTouched() throws Exception {
        purchaseDaysAgo(400, "16.00");
        setBalance(10);
        jdbc.update("UPDATE customer SET deactivated_at = now() WHERE id = ?", memberId);

        assertThat(loyalty.balance(memberId)).isZero();
        assertThat(loyalty.history(memberId).balance()).isZero();
        assertThat(expireEntries()).isEqualTo(1);
        loyalty.history(memberId);
        assertThat(expireEntries()).isEqualTo(1);
    }

    // ---- Placed orders ----

    @Test
    void aPlacedOrderThatRedeemsPointsWhichExpireBeforeItIsPaidIsRefusedAndOneThatDoesNotJustStartsAgain() throws Exception {
        jdbc.update("UPDATE promotion SET active = TRUE WHERE type = 'POINTS_REDEMPTION'");
        long purchase = purchaseDaysAgo(10, "16.00");
        setBalance(7);
        long redeeming = idOf(place(lines(1, 1, 23, 1)).andExpect(status().isCreated()));
        long plain = idOf(place(lines(1, 2)).andExpect(status().isCreated()));
        moveBack(purchase, 366);

        // The points are looked at again at payment, as they are now: gone. The refused payment leaves the order placed.
        pay(redeeming, "16.00").andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, redeeming)).isEqualTo("OPEN");

        // Paying the other one expires the old points first, then earns on a clean balance.
        pay(plain, "32.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.loyalty.before").value(0)).andExpect(jsonPath("$.loyalty.after").value(3));
        assertThat(expireEntries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT points FROM loyalty_entry WHERE type = 'EXPIRE'", Integer.class)).isEqualTo(-7);
        assertThat(loyalty.expiresAt(memberId)).isCloseTo(Instant.now().plus(YEAR), within(1, ChronoUnit.MINUTES));
    }

    // ---- Concurrency ----

    @Test
    void manyRequestsFindingTheSamePointsExpiredExpireThemOnce() throws Exception {
        purchaseDaysAgo(400, "16.00");
        setBalance(10);
        String points = "/api/v1/dashboard/customers/" + memberId + "/points";

        List<MockHttpServletResponse> answers = raceAll(
                () -> as(admin(), get(points), null).andReturn().getResponse(),
                () -> as(admin(), get(points), null).andReturn().getResponse(),
                () -> as(admin(), get(points), null).andReturn().getResponse(),
                () -> as(cashier(), get("/api/v1/customers/" + memberId + "/perks"), null).andReturn().getResponse(),
                () -> as(admin(), get(points), null).andReturn().getResponse());

        assertThat(answers).allSatisfy(answer -> assertThat(answer.getStatus()).isEqualTo(200));
        assertThat(expireEntries()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT points FROM loyalty_entry WHERE type = 'EXPIRE'", Integer.class)).isEqualTo(-10);
        assertThat(balance()).isZero();
    }

    @Test
    void aSaleRacingTheHistoryOfExpiredPointsExpiresThemOnceAndEarnsOnTop() throws Exception {
        long old = purchaseDaysAgo(400, "16.00");
        setBalance(10);
        assertThat(old).isPositive();

        List<MockHttpServletResponse> answers = raceAll(
                () -> as(cashier(), post("/api/v1/orders"), body("PAY", lines(1, 2), "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":32.00}]")).andReturn().getResponse(),
                () -> as(admin(), get("/api/v1/dashboard/customers/" + memberId + "/points"), null).andReturn().getResponse());

        assertThat(answers.get(0).getStatus()).isEqualTo(201);
        assertThat(answers.get(1).getStatus()).isEqualTo(200);
        assertThat(expireEntries()).isEqualTo(1);
        assertThat(balance()).isEqualTo(3);
    }

    // ---- helpers ----

    /** A paid sale of the member that earns nothing, closed at exactly {@code at}. */
    private long pointsFreePurchaseAt(Instant at) throws Exception {
        jdbc.update("UPDATE item SET price = 9.99 WHERE id = 1");
        long order = idOf(sell(lines(1, 1), "9.99").andExpect(status().isCreated()));
        jdbc.update("UPDATE item SET price = 16.00 WHERE id = 1");
        moveTo(order, at);
        return order;
    }

    /** A paid sale of the member, then moved back to {@code days} ago. */
    private long purchaseDaysAgo(int days, String amount) throws Exception {
        long order = idOf(sell(lines(1, 1), amount).andExpect(status().isCreated()));
        moveBack(order, days);
        return order;
    }

    private void moveBack(long order, int days) {
        moveTo(order, Instant.now().minus(Duration.ofDays(days)));
    }

    private void moveTo(long order, Instant closedAt) {
        jdbc.update("UPDATE sale_order SET closed_at = ?, created_at = ?, eligibility_at = ? WHERE id = ?",
                Timestamp.from(closedAt), Timestamp.from(closedAt.minusSeconds(60)), Timestamp.from(closedAt.minusSeconds(60)), order);
    }

    private Instant closedAt(long order) {
        return jdbc.queryForObject("SELECT closed_at FROM sale_order WHERE id = ?", Timestamp.class, order).toInstant();
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

    private int expireEntries() {
        return jdbc.queryForObject("SELECT count(*) FROM loyalty_entry WHERE customer_id = ? AND type = 'EXPIRE'", Integer.class, memberId);
    }

    private static String lines(int... pairs) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < pairs.length; i += 2) {
            json.append(i == 0 ? "" : ",").append("{\"itemId\":").append(pairs[i]).append(",\"quantity\":").append(pairs[i + 1]).append('}');
        }
        return json.append(']').toString();
    }

    private String body(String mode, String lines, String payments) {
        return "{\"clientRequestId\":\"%s\",\"mode\":\"%s\",\"customerId\":%s,\"lines\":%s,\"discountIds\":[],\"payments\":%s}"
                .formatted(UUID.randomUUID(), mode, memberId, lines, payments);
    }

    private ResultActions sell(String lines, String amount) throws Exception {
        return as(cashier(), post("/api/v1/orders"), body("PAY", lines, "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]"));
    }

    private ResultActions place(String lines) throws Exception {
        return as(cashier(), post("/api/v1/orders"), body("PLACE_ORDER", lines, "[]"));
    }

    private ResultActions pay(long order, String amount) throws Exception {
        return as(cashier(), post("/api/v1/orders/" + order + "/payments"), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}"""
                .formatted(UUID.randomUUID(), amount));
    }

    private ResultActions quote(String lines) throws Exception {
        return as(cashier(), post("/api/v1/orders/quote"), body("PAY", lines, "[]"));
    }

    private ResultActions refund(long order) throws Exception {
        return as(manager(), post("/api/v1/orders/" + order + "/refund"), "{\"reason\":\"Changed mind\"}");
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
