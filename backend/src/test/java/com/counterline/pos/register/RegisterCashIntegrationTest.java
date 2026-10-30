package com.counterline.pos.register;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The register's opening float and its expected cash:
 * opening float + cash collected − cash refunded + pay-ins − pay-outs. A pay-out may not take more than
 * that; a refund is owed and may take it below zero.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class RegisterCashIntegrationTest {

    private static final long CARD = 2;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    PlatformTransactionManager transactions;

    private Long cashierId;
    private Long managerId;
    private long cash;

    @BeforeEach
    void aCashierAManagerAndTheCashMethod() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // also registers and orders
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        cash = jdbc.queryForObject("SELECT id FROM payment_method WHERE kind = 'CASH' ORDER BY id LIMIT 1", Long.class);
    }

    // ---- The opening float ----

    @Test
    void aRegisterOpensWithTheFloatDeclaredAndZeroWhenNoneIs() throws Exception {
        open("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.openingFloat").value(0.0)).andExpect(jsonPath("$.expectedCash").value(0.0));
        closeIt();

        open("{}").andExpect(status().isCreated()).andExpect(jsonPath("$.openingFloat").value(0.0));
        closeIt();

        open("{\"openingFloat\":null}").andExpect(status().isCreated()).andExpect(jsonPath("$.openingFloat").value(0.0));
        closeIt();

        open("{\"openingFloat\":150.5}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.openingFloat").value(150.5)).andExpect(jsonPath("$.expectedCash").value(150.5));
        register().andExpect(jsonPath("$.session.openingFloat").value(150.5))
                .andExpect(jsonPath("$.session.expectedCash").value(150.5));
        assertThat(jdbc.queryForObject("SELECT opening_float FROM register_session WHERE closed_at IS NULL",
                java.math.BigDecimal.class)).isEqualByComparingTo("150.50");
    }

    @Test
    void theFloatIsValidatedLikeOtherMoneyInputs() throws Exception {
        for (String body : List.of("{\"openingFloat\":-0.01}", "{\"openingFloat\":-5}", "{\"openingFloat\":1.234}",
                "{\"openingFloat\":\"abc\"}", "{\"openingFloat\":12345678901.00}")) {
            open(body).andExpect(status().isBadRequest());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session", Integer.class)).isZero();
        // Whole ringgit and sen both fit.
        open("{\"openingFloat\":200}").andExpect(status().isCreated()).andExpect(jsonPath("$.openingFloat").value(200.0));
    }

    @Test
    void theDatabaseRefusesANegativeFloat() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO register_session (outlet_id, opened_at, opened_by, opening_float) VALUES (1, now(), ?, -1)""",
                cashierId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theFloatIsNotChangedAfterOpeningAndNothingCarriesOverToTheNextSession() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());

        // Opening again is a conflict, and the float it carried is ignored.
        open("{\"openingFloat\":999}").andExpect(status().isConflict());
        movement("IN", "10", "Top-up").andExpect(status().isOk()).andExpect(jsonPath("$.openingFloat").value(100.0));
        cashSale();
        register().andExpect(jsonPath("$.session.openingFloat").value(100.0))
                .andExpect(jsonPath("$.session.expectedCash").value(139.0));
        closeIt();

        // 139.00 was in the drawer at closing; the next session starts from what is declared, not from that.
        register().andExpect(jsonPath("$.lastClosed.expectedCash").value(139.0));
        open("").andExpect(status().isCreated())
                .andExpect(jsonPath("$.openingFloat").value(0.0)).andExpect(jsonPath("$.expectedCash").value(0.0));
    }

    @Test
    void aSessionThatWasOpenBeforeTheFloatExistedCountsAsAFloatOfZero() throws Exception {
        TestRegister.open(jdbc, cashierId); // inserted without a float, as every earlier session was

        register().andExpect(jsonPath("$.session.openingFloat").value(0.0))
                .andExpect(jsonPath("$.session.expectedCash").value(0.0));
        movement("OUT", "1", "Supplies").andExpect(status().isConflict());
        movement("IN", "5", "Float").andExpect(status().isOk());
        movement("OUT", "5", "Supplies").andExpect(status().isOk());
    }

    // ---- The pay-out ceiling ----

    @Test
    void aPayOutUpToTheExpectedCashIsAllowedAndOneCentOverIsNot() throws Exception {
        open("{\"openingFloat\":20}").andExpect(status().isCreated());

        movement("OUT", "20.01", "Too much").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Pay-out of RM20.01 is more than the register's expected cash of RM20.00"));
        assertThat(movementCount()).isZero();

        movement("OUT", "20", "All of it").andExpect(status().isOk())
                .andExpect(jsonPath("$.payOuts").value(20.0)).andExpect(jsonPath("$.expectedCash").value(0.0));
        movement("OUT", "0.01", "Nothing left").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("expected cash of RM0.00")));
        assertThat(movementCount()).isEqualTo(1);
    }

    @Test
    void onlyCashRaisesTheExpectedCash() throws Exception {
        open("").andExpect(status().isCreated());

        cardSale();
        register().andExpect(jsonPath("$.session.expectedCash").value(0.0))
                .andExpect(jsonPath("$.session.sales.totalCollected").value(29.0));
        movement("OUT", "1", "Supplies").andExpect(status().isConflict());

        cashSale();
        register().andExpect(jsonPath("$.session.expectedCash").value(29.0));
        movement("OUT", "29.01", "Supplies").andExpect(status().isConflict());
        movement("OUT", "29", "Supplies").andExpect(status().isOk());
    }

    @Test
    void payInsRaiseTheExpectedCashWithoutAnyLimit() throws Exception {
        open("").andExpect(status().isCreated());
        movement("IN", "100000.50", "Big float").andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(100000.5));
        movement("OUT", "100000.50", "And back").andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(0.0));
    }

    // ---- Refunds ----

    @Test
    void aCashRefundLowersTheExpectedCashAndIsNeverBlockedEvenBelowZero() throws Exception {
        open("").andExpect(status().isCreated());
        long order = cashSale();
        movement("OUT", "29", "Banked").andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(0.0));

        // The sale is refunded though the drawer no longer holds its cash: owed, so allowed.
        refund(order).andExpect(status().isOk());
        register().andExpect(jsonPath("$.session.expectedCash").value(-29.0));

        movement("OUT", "1", "Supplies").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Pay-out of RM1.00 is more than the register's expected cash of (RM29.00)"));
        movement("IN", "40", "Cash put back").andExpect(status().isOk()).andExpect(jsonPath("$.expectedCash").value(11.0));
        movement("OUT", "11", "Supplies").andExpect(status().isOk());
    }

    @Test
    void aCardRefundDoesNotLowerTheExpectedCash() throws Exception {
        open("{\"openingFloat\":30}").andExpect(status().isCreated());
        long order = cardSale();

        refund(order).andExpect(status().isOk());

        register().andExpect(jsonPath("$.session.expectedCash").value(30.0));
    }

    @Test
    void aRefundOfAnEarlierSessionsSaleComesOutOfTheSessionItIsMadeIn() throws Exception {
        open("").andExpect(status().isCreated());
        long order = cashSale();
        closeIt();

        open("{\"openingFloat\":50}").andExpect(status().isCreated());
        refund(order).andExpect(status().isOk());

        register().andExpect(jsonPath("$.session.expectedCash").value(21.0));
        movement("OUT", "21.01", "Supplies").andExpect(status().isConflict());
        movement("OUT", "21", "Supplies").andExpect(status().isOk());
    }

    // ---- Concurrency (every wait bounded) ----

    @RepeatedTest(3)
    void twoPayOutsCannotSpendTheSameCash() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());

        List<Integer> statuses = together(List.of(
                () -> movement("OUT", "60", "First").andReturn().getResponse().getStatus(),
                () -> movement("OUT", "60", "Second").andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(movementCount()).isEqualTo(1);
        register().andExpect(jsonPath("$.session.payOuts").value(60.0)).andExpect(jsonPath("$.session.expectedCash").value(40.0));
    }

    @Test
    void aPayOutWaitingOnACloseThatWinsIsRefusedAndNeverAttachesToTheClosedSession() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // A close, as it stands in the database: holding the session row, then closing it.
            Future<?> closing = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM register_session WHERE closed_at IS NULL FOR UPDATE", Long.class);
                locked.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("UPDATE register_session SET closed_at = now(), closed_by = opened_by, version = version + 1 WHERE closed_at IS NULL");
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> payingOut = pool.submit(() -> movement("OUT", "10", "Supplies").andReturn().getResponse().getStatus());

            // Release the close only once the pay-out is queued behind its lock.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%register_session%FOR UPDATE%'",
                    Integer.class) < 1) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(25);
            }
            release.countDown();
            closing.get(20, TimeUnit.SECONDS);

            assertThat(payingOut.get(20, TimeUnit.SECONDS)).isEqualTo(422);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(movementCount()).isZero();
    }

    @RepeatedTest(3)
    void manyPayOutsTogetherNeverTakeMoreThanTheDrawerHolds() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());
        List<Callable<Integer>> payOuts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            payOuts.add(() -> movement("OUT", "30", "Supplies").andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = together(payOuts);

        assertThat(statuses.stream().filter(code -> code == 200).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(3);
        register().andExpect(jsonPath("$.session.expectedCash").value(10.0));
    }

    @RepeatedTest(3)
    void aPayOutRacingACashSaleNeverLeavesTheDrawerBelowZero() throws Exception {
        open("").andExpect(status().isCreated());

        List<Integer> statuses = together(List.of(
                () -> movement("OUT", "29", "Supplies").andReturn().getResponse().getStatus(),
                () -> {
                    cashSale();
                    return 201;
                }));

        // Either the sale was in first and the pay-out took its cash, or the pay-out came first and was refused.
        String expected = JsonPath.read(register().andReturn().getResponse().getContentAsString(), "$.session.expectedCash").toString();
        assertThat(new java.math.BigDecimal(expected)).isGreaterThanOrEqualTo(java.math.BigDecimal.ZERO);
        assertThat(statuses.get(0)).isIn(200, 409);
        assertThat(movementCount()).isEqualTo(statuses.get(0) == 200 ? 1 : 0);
    }

    // ---- helpers ----

    private ResultActions open(String json) throws Exception {
        return perform(post("/api/v1/register/open"), json);
    }

    private ResultActions register() throws Exception {
        return mockMvc.perform(get("/api/v1/register").with(cashier()));
    }

    private ResultActions movement(String direction, String amount, String reason) throws Exception {
        return perform(post("/api/v1/register/movements"),
                "{\"direction\":\"" + direction + "\",\"amount\":" + amount + ",\"reason\":\"" + reason + "\"}");
    }

    private void closeIt() throws Exception {
        long version = ((Number) JsonPath.read(register().andReturn().getResponse().getContentAsString(), "$.session.version")).longValue();
        perform(post("/api/v1/register/close"), "{\"version\":" + version + "}").andExpect(status().isOk());
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM register_movement", Integer.class);
    }

    private long cashSale() throws Exception {
        return sale(cash);
    }

    private long cardSale() throws Exception {
        return sale(CARD);
    }

    /** A RM29.00 sale (Tin Caramel) paid in full by {@code method}. */
    private long sale(long method) throws Exception {
        String body = perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":29}]}"""
                .formatted(UUID.randomUUID(), method)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions refund(long order) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/" + order + "/refund").with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer returned it\"}"));
    }

    /** Runs the tasks at once and collects their results; every wait is bounded, so a stuck one fails instead of hanging. */
    private static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> outcomes = new ArrayList<>();
            for (Callable<T> task : tasks) {
                outcomes.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String json) throws Exception {
        MockHttpServletRequestBuilder withAuth = request.with(cashier());
        return mockMvc.perform(json.isEmpty() ? withAuth : withAuth.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
