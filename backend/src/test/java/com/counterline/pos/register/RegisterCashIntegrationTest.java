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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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

        Queued<Integer> payOut = queuedBehindTheSessionLock(true, () -> movement("OUT", "10", "Supplies").andReturn().getResponse().getStatus());

        assertThat(payOut.result()).isEqualTo(422);
        assertThat(movementCount()).isZero();
    }

    @Test
    void aPayInWaitingOnACloseThatWinsIsRefusedAndNeverAttachesToTheClosedSession() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());

        Queued<Integer> payIn = queuedBehindTheSessionLock(true, () -> movement("IN", "10", "Float").andReturn().getResponse().getStatus());

        assertThat(payIn.result()).isEqualTo(422);
        assertThat(movementCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session WHERE closed_at IS NULL", Integer.class)).isZero();
    }

    @Test
    void aPaymentWaitingOnACloseThatWinsIsRefusedAndRecordsNothing() throws Exception {
        open("").andExpect(status().isCreated());

        Queued<Integer> payment = queuedBehindTheSessionLock(true, () -> placeSale(cash).andReturn().getResponse().getStatus());

        assertThat(payment.result()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Integer.class)).isZero();
    }

    @Test
    void aRefundWaitingOnACloseThatWinsIsRefusedAndTheOrderStaysPaid() throws Exception {
        open("").andExpect(status().isCreated());
        long order = cashSale();

        Queued<Integer> refund = queuedBehindTheSessionLock(true, () -> refund(order).andReturn().getResponse().getStatus());

        assertThat(refund.result()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT refunded_at FROM sale_order WHERE id = ?", java.sql.Timestamp.class, order)).isNull();
    }

    @Test
    void aPaymentAndARefundAreDatedOnlyOnceTheRegisterIsLocked() throws Exception {
        open("").andExpect(status().isCreated());

        // The session is held without closing it; the sale is queued behind the lock and released later.
        Queued<Long> sale = queuedBehindTheSessionLock(false, this::cashSale);
        Instant paidAt = jdbc.queryForObject("SELECT closed_at FROM sale_order WHERE id = ?", java.sql.Timestamp.class, sale.result()).toInstant();
        assertThat(paidAt).isAfter(sale.releasedAt());

        Queued<Integer> refund = queuedBehindTheSessionLock(false, () -> refund(sale.result()).andReturn().getResponse().getStatus());
        Instant refundedAt = jdbc.queryForObject("SELECT refunded_at FROM sale_order WHERE id = ?", java.sql.Timestamp.class, sale.result()).toInstant();
        assertThat(refund.result()).isEqualTo(200);
        assertThat(refundedAt).isAfter(refund.releasedAt());
    }

    @RepeatedTest(3)
    void aPayInRacingTheCloseLandsBeforeItOrIsRefusedAndNeverAfter() throws Exception {
        open("").andExpect(status().isCreated());
        View seen = view();

        List<Integer> statuses = together(List.of(
                () -> close(seen).andReturn().getResponse().getStatus(),
                () -> movement("IN", "10", "Float").andReturn().getResponse().getStatus()));

        // The pay-in came first (the close is then stale) or the close did (the pay-in finds the register closed).
        assertThat(statuses).isIn(List.of(200, 422), List.of(409, 200));
        assertThat(movementCount()).isEqualTo(statuses.get(1) == 200 ? 1 : 0);
        assertNothingFollowsTheClose();
    }

    @RepeatedTest(3)
    void aCashSaleRacingTheCloseLandsBeforeItOrIsRefusedAndNeverAfter() throws Exception {
        open("").andExpect(status().isCreated());
        View seen = view();

        List<Integer> statuses = together(List.of(
                () -> close(seen).andReturn().getResponse().getStatus(),
                () -> placeSale(cash).andReturn().getResponse().getStatus()));

        assertThat(statuses).isIn(List.of(200, 422), List.of(409, 201));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order WHERE status = 'CLOSED'", Integer.class))
                .isEqualTo(statuses.get(1) == 201 ? 1 : 0);
        assertNothingFollowsTheClose();
    }

    @Test
    void aCloseNamesTheSessionAndItsVersion() throws Exception {
        open("").andExpect(status().isCreated());
        View seen = view();

        perform(post("/api/v1/register/close"), "{\"version\":" + seen.version() + "}").andExpect(status().isBadRequest());
        perform(post("/api/v1/register/close"), "{\"sessionId\":" + seen.id() + "}").andExpect(status().isBadRequest());
        perform(post("/api/v1/register/close"), "{\"sessionId\":" + (seen.id() + 1) + ",\"version\":" + seen.version() + "}")
                .andExpect(status().isConflict());
        register().andExpect(jsonPath("$.open").value(true));

        close(seen).andExpect(status().isOk());
    }

    @Test
    void aScreenFromAnEarlierSessionCannotCloseTheNextOne() throws Exception {
        open("").andExpect(status().isCreated());
        View earlier = view();
        close(earlier).andExpect(status().isOk());
        open("").andExpect(status().isCreated());
        View current = view();
        // Both are a first view of a session (version 0): only the session id tells them apart.
        assertThat(current.version()).isEqualTo(earlier.version());
        assertThat(current.id()).isNotEqualTo(earlier.id());

        close(earlier).andExpect(status().isConflict());
        register().andExpect(jsonPath("$.open").value(true));

        close(current).andExpect(status().isOk());
        close(earlier).andExpect(status().isUnprocessableContent());
    }

    @Test
    void anyDrawerEventMakesAnEarlierViewOfTheRegisterStale() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());
        AtomicLong sold = new AtomicLong();
        Map<String, Callable<?>> events = new LinkedHashMap<>();
        events.put("pay-in", () -> movement("IN", "5", "Float").andExpect(status().isOk()));
        events.put("pay-out", () -> movement("OUT", "5", "Supplies").andExpect(status().isOk()));
        events.put("cash sale", () -> {
            sold.set(cashSale());
            return null;
        });
        events.put("card sale", () -> cardSale());
        events.put("refund", () -> refund(sold.get()).andExpect(status().isOk()));

        for (Map.Entry<String, Callable<?>> event : events.entrySet()) {
            View seen = view();
            event.getValue().call();

            assertThat(view().version()).as("version after a " + event.getKey()).isGreaterThan(seen.version());
            close(seen).andExpect(status().isConflict());
            register().andExpect(jsonPath("$.open").value(true));
        }
        close(view()).andExpect(status().isOk());
    }

    @Test
    void aPlacedOrderIsNotADrawerEventSoItLeavesTheViewCurrent() throws Exception {
        open("").andExpect(status().isCreated());
        View seen = view();
        perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"payments":[]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated());

        assertThat(view().version()).isEqualTo(seen.version());
    }

    // ---- The version moves once per successful drawer event, and never for a failed or replayed one ----

    @Test
    void eachSuccessfulDrawerEventAdvancesTheVersionExactlyOnce() throws Exception {
        open("{\"openingFloat\":100}").andExpect(status().isCreated());
        long version = sessionVersion();

        movement("IN", "5", "Float").andExpect(status().isOk());
        assertThat(sessionVersion()).as("after a pay-in").isEqualTo(version + 1);
        movement("OUT", "5", "Supplies").andExpect(status().isOk());
        assertThat(sessionVersion()).as("after a pay-out").isEqualTo(version + 2);
        long order = cashSale();
        assertThat(sessionVersion()).as("after a sale").isEqualTo(version + 3);
        refund(order).andExpect(status().isOk());
        assertThat(sessionVersion()).as("after a refund").isEqualTo(version + 4);
        // What the screen is shown is the stored version.
        assertThat(view().version()).isEqualTo(version + 4);
    }

    @Test
    void aRefusedPayOutLeavesTheVersionAlone() throws Exception {
        open("{\"openingFloat\":10}").andExpect(status().isCreated());
        long version = sessionVersion();

        movement("OUT", "10.01", "Supplies").andExpect(status().isConflict());

        assertThat(sessionVersion()).isEqualTo(version);
        assertThat(movementCount()).isZero();
    }

    @Test
    void aPaymentThatDoesNotMatchTheTotalLeavesTheVersionAlone() throws Exception {
        open("").andExpect(status().isCreated());
        long version = sessionVersion();

        perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":10}]}"""
                .formatted(UUID.randomUUID(), cash)).andExpect(status().isUnprocessableContent());

        assertThat(sessionVersion()).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order", Integer.class)).isZero();
    }

    @Test
    void aFailedRefundLeavesTheVersionAlone() throws Exception {
        open("").andExpect(status().isCreated());
        long paid = cashSale();
        long placed = placedOrder();
        long version = sessionVersion();

        refund(placed).andExpect(status().isUnprocessableContent()); // not paid
        assertThat(sessionVersion()).isEqualTo(version);

        refund(paid).andExpect(status().isOk());
        long afterRefund = sessionVersion();
        assertThat(afterRefund).isEqualTo(version + 1);
        refund(paid).andExpect(status().isUnprocessableContent()); // already refunded
        assertThat(sessionVersion()).isEqualTo(afterRefund);
    }

    @Test
    void aReplayedCheckoutAndAReplayedPaymentLeaveTheVersionAlone() throws Exception {
        open("").andExpect(status().isCreated());
        long version = sessionVersion();

        String checkout = """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":29}]}""".formatted(UUID.randomUUID(), cash);
        perform(post("/api/v1/orders"), checkout).andExpect(status().isCreated());
        assertThat(sessionVersion()).isEqualTo(version + 1);
        perform(post("/api/v1/orders"), checkout).andExpect(status().isOk());
        assertThat(sessionVersion()).as("after a replayed checkout").isEqualTo(version + 1);

        long placed = placedOrder();
        assertThat(sessionVersion()).as("placing an order is not a drawer event").isEqualTo(version + 1);
        String payment = """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":%d,"reference":"ref","amount":29}]}"""
                .formatted(UUID.randomUUID(), cash);
        perform(post("/api/v1/orders/" + placed + "/payments"), payment).andExpect(status().is2xxSuccessful());
        assertThat(sessionVersion()).isEqualTo(version + 2);
        perform(post("/api/v1/orders/" + placed + "/payments"), payment).andExpect(status().is2xxSuccessful());
        assertThat(sessionVersion()).as("after a replayed payment").isEqualTo(version + 2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment", Integer.class)).isEqualTo(2);
    }

    // ---- Split payments ----

    @Test
    void aSplitSaleAddsOnlyItsCashPartToTheDrawer() throws Exception {
        open("{\"openingFloat\":50}").andExpect(status().isCreated());

        long order = splitSale(10, 19);

        register().andExpect(jsonPath("$.session.expectedCash").value(60.0));
        // The card part is not in the drawer to be paid out.
        movement("OUT", "60.01", "Supplies").andExpect(status().isConflict());
        movement("OUT", "60", "Supplies").andExpect(status().isOk());
        movement("IN", "60", "Back").andExpect(status().isOk());

        refund(order).andExpect(status().isOk());
        register().andExpect(jsonPath("$.session.expectedCash").value(50.0));
    }

    @Test
    void aSplitRefundComesOutOfTheSessionItIsMadeInAndOnlyForTheCashPart() throws Exception {
        open("{\"openingFloat\":50}").andExpect(status().isCreated());
        long order = splitSale(10, 19);
        closeIt();

        open("").andExpect(status().isCreated());
        refund(order).andExpect(status().isOk());

        // RM10.00 of cash goes back, never the card's RM19.00; a refund is owed even below zero.
        register().andExpect(jsonPath("$.session.expectedCash").value(-10.0));
        closeIt();
        register().andExpect(jsonPath("$.lastClosed.expectedCash").value(-10.0));
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

    /** The open session as a screen shows it: what a close has to name. */
    private record View(long id, long version) {
    }

    private View view() throws Exception {
        String body = register().andReturn().getResponse().getContentAsString();
        return new View(((Number) JsonPath.read(body, "$.session.id")).longValue(),
                ((Number) JsonPath.read(body, "$.session.version")).longValue());
    }

    private ResultActions close(View seen) throws Exception {
        return perform(post("/api/v1/register/close"), "{\"sessionId\":" + seen.id() + ",\"version\":" + seen.version() + "}");
    }

    /** The stored version of the open session. */
    private long sessionVersion() {
        return jdbc.queryForObject("SELECT version FROM register_session WHERE closed_at IS NULL", Long.class);
    }

    /** A placed (unpaid) RM29.00 order. */
    private long placedOrder() throws Exception {
        String body = perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"payments":[]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void closeIt() throws Exception {
        close(view()).andExpect(status().isOk());
    }

    /** No movement or sale was made after its session closed. */
    private void assertNothingFollowsTheClose() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM register_movement m JOIN register_session s ON s.id = m.session_id
                WHERE s.closed_at IS NOT NULL AND m.created_at > s.closed_at""", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sale_order o JOIN register_session s ON s.outlet_id = o.outlet_id
                WHERE s.closed_at IS NOT NULL AND o.closed_at > s.closed_at""", Integer.class)).isZero();
    }

    /** An action's result, and when the session lock it queued behind was let go. */
    private record Queued<T>(T result, Instant releasedAt) {
    }

    /**
     * Holds the open session's row locked as a close does (and closes it if {@code closing}), starts the
     * action, and lets the lock go only once the action is queued behind it. Every wait is bounded.
     */
    private <T> Queued<T> queuedBehindTheSessionLock(boolean closing, Callable<T> action) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> holding = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM register_session WHERE closed_at IS NULL FOR UPDATE", Long.class);
                locked.countDown();
                try {
                    assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (closing) {
                    jdbc.update("UPDATE register_session SET closed_at = now(), closed_by = opened_by, version = version + 1 WHERE closed_at IS NULL");
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<T> running = pool.submit(action);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '%UPDATE register_session SET version = version + 1%'",
                    Integer.class) < 1) {
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.sleep(25);
            }
            Instant releasedAt = Instant.now();
            release.countDown();
            holding.get(20, TimeUnit.SECONDS);
            return new Queued<>(running.get(20, TimeUnit.SECONDS), releasedAt);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** A RM29.00 sale paid part in cash and part by card. */
    private long splitSale(int cashPart, int cardPart) throws Exception {
        String body = perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":%d},
                             {"paymentMethodId":%d,"reference":"ref","amount":%d}]}"""
                .formatted(UUID.randomUUID(), cash, cashPart, CARD, cardPart))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
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
        String body = placeSale(method).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private ResultActions placeSale(long method) throws Exception {
        return perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":29}]}"""
                .formatted(UUID.randomUUID(), method));
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
