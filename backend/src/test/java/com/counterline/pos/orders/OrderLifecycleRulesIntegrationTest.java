package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * An order's life after it is placed: Edit Order (today's prices, same identity, everything replaced), payment
 * retries, races between pay, edit, void and refund (the later one gets the usual domain refusal, never a
 * conflict), the register boundaries, and what a placed order keeps when the catalogue changes. Fixtures:
 * item 1 Pouch Cheese 16.00, item 4 Tin Caramel 29.00, item 7 Gift Box 70.00 (category "Tin", 3 flavours,
 * Matcha +2.00), discounts 1 Staff 30% (line) and 3 Order 10% (order).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/options-fixture.sql"})
class OrderLifecycleRulesIntegrationTest {

    private static final String GIFT_BOX = "{\"itemId\":7,\"quantity\":1,\"modifiers\":[{\"optionId\":1,\"quantity\":1},{\"optionId\":3,\"quantity\":2}]}";

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
    private long second;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO UPDATE SET active = TRUE");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        cashierId = staffRepository.save(new Staff("Lifecycle Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Lifecycle Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    @AfterEach
    void reopenOutlets() {
        jdbc.update("UPDATE outlet SET active = TRUE");
    }

    // ---- Edit Order ----

    @Test
    void editingRepricesAtTodaysPricesAndKeepsTheOrdersIdentity() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        Map<String, Object> before = jdbc.queryForMap("SELECT id, order_number, receipt_code, created_at, staff_id, client_request_id FROM sale_order WHERE id = ?", id);
        jdbc.update("UPDATE item SET price = 35.00 WHERE id = 4");

        as(cashier(), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":4,\"quantity\":1}]", "[]", "[]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.total").value(35.0)).andExpect(jsonPath("$.lines[0].unitPrice").value(35.0));

        assertThat(jdbc.queryForMap("SELECT id, order_number, receipt_code, created_at, staff_id, client_request_id FROM sale_order WHERE id = ?", id))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order", Long.class)).isEqualTo(1L);
        // The cart it is edited as shows today's price too.
        as(cashier(), get("/api/v1/orders/" + id + "/cart"), null).andExpect(status().isOk());
    }

    @Test
    void editingReplacesEveryLineOptionAndDiscountAndLeavesNothingBehind() throws Exception {
        long id = place("[" + GIFT_BOX.replace("\"quantity\":1,\"modifiers\"", "\"quantity\":1,\"discountIds\":[1],\"modifiers\"")
                + ",{\"itemId\":1,\"quantity\":1}]");
        assertThat(counts(id)).containsExactly(2L, 2L, 1L, 0L);

        // One plain line and an order discount: the two lines, their options and the line discount are gone.
        as(cashier(), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":1,\"quantity\":1}]", "[3]", "[]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(14.4))
                .andExpect(jsonPath("$.orderDiscounts[0].name").value("Order 10%"));
        assertThat(counts(id)).containsExactly(1L, 0L, 1L, 1L);

        as(cashier(), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":1,\"quantity\":1}]", "[]", "[]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(16.0)).andExpect(jsonPath("$.orderDiscounts").isEmpty());
        assertThat(counts(id)).containsExactly(1L, 0L, 0L, 0L);
    }

    @Test
    void payingThroughAnEditIsIdempotentPerRequestAndLaterRequestsAreRefused() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        String request = UUID.randomUUID().toString();
        String pay = bodyWith(request, "PAY", "[{\"itemId\":1,\"quantity\":1}]", "[]", payment("16.00"));

        as(cashier(), put("/api/v1/orders/" + id), pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.total").value(16.0));
        as(cashier(), put("/api/v1/orders/" + id), pay).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo(1L);

        // Any other request on the settled order is a plain refusal.
        as(cashier(), put("/api/v1/orders/" + id), body("PAY", "[{\"itemId\":1,\"quantity\":1}]", "[]", payment("16.00")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("not awaiting payment")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void aPaidVoidedOrRefundedOrderCannotBeEditedOrOpenedAsACart() throws Exception {
        long paid = place("[{\"itemId\":1,\"quantity\":1}]");
        pay(paid, UUID.randomUUID().toString(), "16.00").andExpect(status().isOk());
        long voided = place("[{\"itemId\":1,\"quantity\":1}]");
        as(manager(), post("/api/v1/orders/" + voided + "/void"), "{\"reason\":\"Customer left\"}").andExpect(status().isOk());
        long refunded = place("[{\"itemId\":1,\"quantity\":1}]");
        pay(refunded, UUID.randomUUID().toString(), "16.00").andExpect(status().isOk());
        as(manager(), post("/api/v1/orders/" + refunded + "/refund"), "{\"reason\":\"Wrong flavour\"}").andExpect(status().isOk());

        for (long settled : List.of(paid, voided, refunded)) {
            as(cashier(), put("/api/v1/orders/" + settled), body("PLACE_ORDER", "[{\"itemId\":4,\"quantity\":1}]", "[]", "[]"))
                    .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(containsString("not awaiting payment")));
            as(cashier(), get("/api/v1/orders/" + settled + "/cart"), null).andExpect(status().isUnprocessableContent());
        }
        assertThat(jdbc.queryForObject("SELECT total FROM sale_order WHERE id = ?", java.math.BigDecimal.class, paid)).isEqualByComparingTo("16.00");
    }

    @Test
    void anotherOutletCannotEditOpenOrPayAnOrder() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");

        as(cashierAt(second), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":1,\"quantity\":1}]", "[]", "[]"))
                .andExpect(status().isNotFound());
        as(cashierAt(second), get("/api/v1/orders/" + id + "/cart"), null).andExpect(status().isNotFound());
        as(cashierAt(second), post("/api/v1/orders/" + id + "/payments"), payBody(UUID.randomUUID().toString(), "29.00")).andExpect(status().isNotFound());
        as(managerAt(second), post("/api/v1/orders/" + id + "/void"), "{\"reason\":\"Not mine\"}").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT total FROM sale_order WHERE id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("29.00");
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, id)).isEqualTo("OPEN");
    }

    // ---- Races ----

    @Test
    void theSamePayRequestTwiceAtOnceReturnsTheSamePaidOrderAndTakesOnePayment() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        String request = UUID.randomUUID().toString();

        List<MockHttpServletResponse> answers = race(4, () -> pay(id, request, "29.00").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
        for (MockHttpServletResponse answer : answers) {
            assertThat((Number) JsonPath.read(answer.getContentAsString(), "$.id")).extracting(Number::longValue).isEqualTo(id);
            assertThat((String) JsonPath.read(answer.getContentAsString(), "$.status")).isEqualTo("CLOSED");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo(1L);
        // A different request afterwards is a refusal, not another payment.
        pay(id, UUID.randomUUID().toString(), "29.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("not awaiting payment")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    void anEditPayingAndAPaymentAtOnceLetExactlyOneWin() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        String editRequest = UUID.randomUUID().toString();
        String payRequest = UUID.randomUUID().toString();

        List<MockHttpServletResponse> answers = raceAll(
                () -> as(cashier(), put("/api/v1/orders/" + id), bodyWith(editRequest, "PAY", "[{\"itemId\":1,\"quantity\":1}]", "[]", payment("16.00")))
                        .andReturn().getResponse(),
                () -> pay(id, payRequest, "29.00").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, id)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT total - (SELECT sum(amount) FROM payment WHERE order_id = ?) FROM sale_order WHERE id = ?",
                java.math.BigDecimal.class, id, id)).isEqualByComparingTo("0");
    }

    @Test
    void anEditAndAVoidAtOnceNeverConflictAndTheOrderEndsVoided() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");

        List<MockHttpServletResponse> answers = raceAll(
                () -> as(cashier(), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":4,\"quantity\":2}]", "[]", "[]"))
                        .andReturn().getResponse(),
                () -> as(manager(), post("/api/v1/orders/" + id + "/void"), "{\"reason\":\"Changed mind\"}").andReturn().getResponse());

        // The void always applies (before or after the edit); the edit either happened or was told the order is settled.
        assertThat(answers.get(1).getStatus()).isEqualTo(200);
        assertThat(answers.get(0).getStatus()).isIn(200, 422);
        assertThat(answers).extracting(MockHttpServletResponse::getStatus).doesNotContain(409, 500);
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, id)).isEqualTo("VOIDED");
    }

    @Test
    void twoRefundsAtOnceRefundOnceAndTheOtherIsToldItAlreadyWas() throws Exception {
        long id = place("[{\"itemId\":1,\"quantity\":1}]");
        pay(id, UUID.randomUUID().toString(), "16.00").andExpect(status().isOk());

        List<MockHttpServletResponse> answers = race(2, () -> as(manager(), post("/api/v1/orders/" + id + "/refund"),
                "{\"reason\":\"Both tills\"}").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
        assertThat(answers.stream().filter(answer -> answer.getStatus() == 422).findFirst().orElseThrow().getContentAsString())
                .contains("has already been refunded");
        assertThat(jdbc.queryForObject("SELECT refund_reason FROM sale_order WHERE id = ?", String.class, id)).isEqualTo("Both tills");
    }

    @Test
    void aPaymentAndAVoidAtOnceNeverConflict() throws Exception {
        long id = place("[{\"itemId\":1,\"quantity\":1}]");

        List<MockHttpServletResponse> answers = raceAll(
                () -> pay(id, UUID.randomUUID().toString(), "16.00").andReturn().getResponse(),
                () -> as(manager(), post("/api/v1/orders/" + id + "/void"), "{\"reason\":\"Race\"}").andReturn().getResponse());

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 422);
        String status = jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, id);
        assertThat(status).isIn("CLOSED", "VOIDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class, id)).isEqualTo("CLOSED".equals(status) ? 1L : 0L);
    }

    // ---- The register ----

    @Test
    void anEditCannotPlaceOrPayWhileTheRegisterIsClosedAndLeavesTheOrderAsItWas() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        TestRegister.close(jdbc);

        as(cashier(), put("/api/v1/orders/" + id), body("PAY", "[{\"itemId\":1,\"quantity\":1}]", "[]", payment("16.00")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Register closed"));
        as(cashier(), put("/api/v1/orders/" + id), body("PLACE_ORDER", "[{\"itemId\":1,\"quantity\":1}]", "[]", "[]"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Register closed"));
        assertThat(jdbc.queryForObject("SELECT total FROM sale_order WHERE id = ?", java.math.BigDecimal.class, id)).isEqualByComparingTo("29.00");
        assertThat(jdbc.queryForObject("SELECT item_name FROM sale_order_line WHERE order_id = ?", String.class, id)).isEqualTo("Tin Caramel");
        as(cashier(), get("/api/v1/orders/" + id + "/cart"), null).andExpect(status().isOk());
    }

    @Test
    void placingIsRefusedWhenClosedAndVoidingAnUnpaidOrderIsNot() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        TestRegister.close(jdbc);

        as(cashier(), post("/api/v1/orders"), body("PLACE_ORDER", "[{\"itemId\":4,\"quantity\":1}]", "[]", "[]"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Register closed"));
        as(manager(), post("/api/v1/orders/" + id + "/void"), "{\"reason\":\"Closing up\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void anOrderPlacedInOneSessionAndPaidInTheNextBelongsToTheNextOne() throws Exception {
        long id = place("[{\"itemId\":4,\"quantity\":1}]");
        TestRegister.close(jdbc);
        jdbc.update("UPDATE register_session SET opened_at = now() - interval '3 hours', closed_at = now() - interval '2 hours'");
        pay(id, UUID.randomUUID().toString(), "29.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));

        TestRegister.open(jdbc, cashierId); // a new session, opened an hour ago
        pay(id, UUID.randomUUID().toString(), "29.00").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        // Paid inside the second session and outside the first.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sale_order o JOIN register_session s ON s.closed_at IS NULL
                WHERE o.id = ? AND o.closed_at >= s.opened_at""", Long.class, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sale_order o JOIN register_session s ON s.closed_at IS NOT NULL
                WHERE o.id = ? AND o.closed_at BETWEEN s.opened_at AND s.closed_at""", Long.class, id)).isZero();
        // Placed before the second session: it was created in the first one's time.
        assertThat(jdbc.queryForObject("SELECT created_at < closed_at FROM sale_order WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void aRefundNeedsAnOpenRegisterSoItBelongsToASession() throws Exception {
        long id = checkoutPaid("[{\"itemId\":1,\"quantity\":1}]", "16.00");
        TestRegister.close(jdbc);

        as(manager(), post("/api/v1/orders/" + id + "/refund"), "{\"reason\":\"Late\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Register closed"));
        assertThat(jdbc.queryForObject("SELECT refunded_at IS NULL FROM sale_order WHERE id = ?", Boolean.class, id)).isTrue();

        TestRegister.open(jdbc, cashierId);
        as(manager(), post("/api/v1/orders/" + id + "/refund"), "{\"reason\":\"Late\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.reason").value("Late"));
        // Refunding twice is still the plain refusal.
        as(manager(), post("/api/v1/orders/" + id + "/refund"), "{\"reason\":\"Again\"}").andExpect(status().isUnprocessableContent());
    }

    // ---- What a placed order keeps ----

    @Test
    void aPlacedOrderIsPaidAtTheItemOptionAndGroupSnapshotsWhateverTheCatalogueSaysNow() throws Exception {
        long id = place("[" + GIFT_BOX + "]");
        as(cashier(), get("/api/v1/orders/" + id), null).andExpect(jsonPath("$.total").value(74.0));

        // The catalogue moves on: a new price, new names, a renamed folder, an option and group renamed and re-priced.
        jdbc.update("UPDATE item SET price = 90.00, name = 'Gift Box NEW' WHERE id = 7");
        jdbc.update("UPDATE category SET name = 'Renamed Tins' WHERE id = 2");
        jdbc.update("UPDATE modifier_option SET price = 5.00, name = 'Matcha NEW' WHERE id = 3");
        jdbc.update("UPDATE modifier_group SET name = 'Flavours NEW' WHERE id = 1");

        // Today's price is not what is owed.
        pay(id, UUID.randomUUID().toString(), "92.00").andExpect(status().isUnprocessableContent());
        pay(id, UUID.randomUUID().toString(), "74.00").andExpect(status().isOk()).andExpect(jsonPath("$.total").value(74.0))
                .andExpect(jsonPath("$.lines[0].itemName").value("Gift Box"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(70.0))
                .andExpect(jsonPath("$.lines[0].modifierUnitPrice").value(4.0))
                .andExpect(jsonPath("$.lines[0].modifiers[*].optionName").value(org.hamcrest.Matchers.contains("Caramel", "Matcha")));

        assertThat(jdbc.queryForObject("SELECT category_name FROM sale_order_line WHERE order_id = ?", String.class, id)).isEqualTo("Tin");
        assertThat(jdbc.queryForList("SELECT group_name FROM sale_order_line_modifier m JOIN sale_order_line l ON l.id = m.line_id WHERE l.order_id = ?",
                String.class, id)).containsOnly("Flavours");
        assertThat(jdbc.queryForObject("SELECT m.unit_price FROM sale_order_line_modifier m JOIN sale_order_line l ON l.id = m.line_id "
                + "WHERE l.order_id = ? AND m.option_name = 'Matcha'", java.math.BigDecimal.class, id)).isEqualByComparingTo("2.00");
        // A fresh read of the paid order still shows the snapshots.
        as(cashier(), get("/api/v1/orders/" + id), null).andExpect(jsonPath("$.lines[0].itemName").value("Gift Box"))
                .andExpect(jsonPath("$.lines[0].modifiers[1].optionName").value("Matcha"));
    }

    // ---- helpers ----

    /** Places an unpaid order of {@code lines}; its id. */
    private long place(String lines) throws Exception {
        return ((Number) JsonPath.read(as(cashier(), post("/api/v1/orders"), body("PLACE_ORDER", lines, "[]", "[]"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private long checkoutPaid(String lines, String amount) throws Exception {
        return ((Number) JsonPath.read(as(cashier(), post("/api/v1/orders"), body("PAY", lines, "[]", payment(amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions pay(long id, String request, String amount) throws Exception {
        return as(cashier(), post("/api/v1/orders/" + id + "/payments"), payBody(request, amount));
    }

    private static String payBody(String request, String amount) {
        return "{\"clientRequestId\":\"%s\",\"payments\":%s}".formatted(request, payment(amount));
    }

    private static String payment(String amount) {
        return "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":%s}]".formatted(amount);
    }

    private static String body(String mode, String lines, String discountIds, String payments) {
        return bodyWith(UUID.randomUUID().toString(), mode, lines, discountIds, payments);
    }

    private static String bodyWith(String request, String mode, String lines, String discountIds, String payments) {
        return "{\"clientRequestId\":\"%s\",\"mode\":\"%s\",\"lines\":%s,\"discountIds\":%s,\"payments\":%s}"
                .formatted(request, mode, lines, discountIds, payments);
    }

    /** Lines, options, discounts, and order-level discounts of one order. */
    private List<Long> counts(long orderId) {
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM sale_order_line WHERE order_id = ?", Long.class, orderId),
                jdbc.queryForObject("SELECT count(*) FROM sale_order_line_modifier m JOIN sale_order_line l ON l.id = m.line_id WHERE l.order_id = ?", Long.class, orderId),
                jdbc.queryForObject("SELECT count(*) FROM sale_order_discount WHERE order_id = ?", Long.class, orderId),
                jdbc.queryForObject("SELECT count(*) FROM sale_order_discount WHERE order_id = ? AND line_id IS NULL", Long.class, orderId));
    }

    private List<MockHttpServletResponse> race(int callers, Callable<MockHttpServletResponse> call) throws Exception {
        List<Callable<MockHttpServletResponse>> calls = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            calls.add(call);
        }
        return raceAll(calls);
    }

    @SafeVarargs
    private List<MockHttpServletResponse> raceAll(Callable<MockHttpServletResponse>... calls) throws Exception {
        return raceAll(List.of(calls));
    }

    /** All the calls released together, each given 30 seconds; the answers in the order of the calls. */
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

    private ResultActions as(RequestPostProcessor who, MockHttpServletRequestBuilder request, String json) throws Exception {
        request.with(who);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return cashierAt(1L);
    }

    private RequestPostProcessor cashierAt(long outlet) {
        return jwt().jwt(token -> token.claim("outlet", outlet).subject(cashierId.toString()).claim("name", "Lifecycle Cashier"));
    }

    private RequestPostProcessor manager() {
        return managerAt(1L);
    }

    private RequestPostProcessor managerAt(long outlet) {
        return jwt().jwt(token -> token.claim("outlet", outlet).subject(managerId.toString()).claim("role", "MANAGER").claim("name", "Lifecycle Manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
