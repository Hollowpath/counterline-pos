package com.counterline.pos.orders;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.TestDevices;
import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.orders.OrderDtos.PayOrderRequest;
import com.counterline.pos.orders.OrderDtos.PaymentRequest;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Past orders against PostgreSQL: place now / pay later, manager-only void and refund,
 * special access, search, and concurrent changes. Fixture: item 1 Pouch Cheese 16.00,
 * item 4 Tin Caramel 29.00; payment method 2 is Visa.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class OrderLifecycleApiIntegrationTest {

    private static final long VISA = 2;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    OrderLifecycleService lifecycle;

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    JwtDecoder jwtDecoder;

    private Long cashierId;
    private Long managerId;

    @BeforeEach
    void createStaff() {
        jdbc.execute("TRUNCATE login_throttle, staff RESTART IDENTITY CASCADE"); // cascades to orders and payments
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
    }

    // ---- Place Order, pay later ----

    @Test
    void placeOrderSavesAnUnpaidOpenOrder() throws Exception {
        long id = placeOrder(4, 1);

        getOrder(id)
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.total").value(29.0))
                .andExpect(jsonPath("$.payments").value(empty()));
    }

    @Test
    void placeOrderTakesNoPayments() throws Exception {
        perform(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":29.00}]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Payments are not taken when placing an order"));
    }

    @Test
    void anOpenOrderIsPaidLaterAndARetryReturnsThePaidOrder() throws Exception {
        long id = placeOrder(4, 1);
        String payment = payBody(UUID.randomUUID(), "29.00");

        pay(id, payment).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.payments[0].method").value("Visa"));
        pay(id, payment).andExpect(status().isOk()).andExpect(jsonPath("$.payments", hasSize(1)));

        pay(id, payBody(UUID.randomUUID(), "29.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("is not awaiting payment")));
    }

    @Test
    void aWrongPaymentAmountLeavesTheOrderOpen() throws Exception {
        long id = placeOrder(4, 1);

        pay(id, payBody(UUID.randomUUID(), "20.00")).andExpect(status().isUnprocessableContent());

        getOrder(id).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.payments").value(empty()));
    }

    // ---- Void (manager only, unpaid orders) ----

    @Test
    void staffCannotVoid() throws Exception {
        long id = placeOrder(4, 1);

        perform(post("/api/v1/orders/" + id + "/void"), cashier(), "{\"reason\":\"Wrong key in\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Access denied"));
    }

    @Test
    void aManagerVoidsAnOpenOrderWithAReason() throws Exception {
        long id = placeOrder(4, 1);

        perform(post("/api/v1/orders/" + id + "/void"), manager(), "{\"reason\":\"  Wrong key in \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VOIDED"))
                .andExpect(jsonPath("$.voided.reason").value("Wrong key in"))
                .andExpect(jsonPath("$.voided.voidedBy").value("Test Manager"));
    }

    @Test
    void voidNeedsAReasonAndAnUnpaidOrder() throws Exception {
        long open = placeOrder(4, 1);
        perform(post("/api/v1/orders/" + open + "/void"), manager(), "{\"reason\":\" \"}")
                .andExpect(status().isBadRequest());

        long paid = payNow(4, 1, "29.00");
        perform(post("/api/v1/orders/" + paid + "/void"), manager(), "{\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("issue a refund instead")));
    }

    // ---- Refund (manager only, paid orders, full amount) ----

    @Test
    void staffCannotRefund() throws Exception {
        long id = payNow(4, 1, "29.00");

        perform(post("/api/v1/orders/" + id + "/refund"), cashier(), "{\"reason\":\"Damaged\"}")
                .andExpect(status().isForbidden());
    }

    @Test
    void aManagerRefundsAPaidOrderInFullOnce() throws Exception {
        long id = payNow(4, 2, "58.00");

        perform(post("/api/v1/orders/" + id + "/refund"), manager(), "{\"reason\":\"Damaged tin\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.refund.amount").value(58.0))
                .andExpect(jsonPath("$.refund.reason").value("Damaged tin"))
                .andExpect(jsonPath("$.refund.refundedBy").value("Test Manager"));

        perform(post("/api/v1/orders/" + id + "/refund"), manager(), "{\"reason\":\"again\"}")
                .andExpect(status().isUnprocessableContent());
    }

    // ---- Special access: manager approves at a cashier's terminal ----

    @Test
    void aManagerPinGrantsAShortLivedManagerTokenWithoutEndingTheCashierSession() throws Exception {
        String cashierToken = login("123456");
        long id = placeOrder(4, 1);

        String body = mockMvc.perform(post("/api/v1/auth/special-access")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"654321\",\"operation\":\"VOID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.role").value("MANAGER"))
                .andReturn().getResponse().getContentAsString();
        String managerToken = JsonPath.read(body, "$.token");

        mockMvc.perform(post("/api/v1/orders/" + id + "/void")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer left\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voided.voidedBy").value("Test Manager"));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken))
                .andExpect(jsonPath("$.fullName").value("Test Cashier"));

        // The approval was for that one void: not another, nor anything else.
        long another = placeOrder(4, 1);
        mockMvc.perform(post("/api/v1/orders/" + another + "/void")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Again\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Approval used"));
        mockMvc.perform(get("/api/v1/dashboard/live").header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/register").header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNonManagerOrWrongPinIsRefusedWith403() throws Exception {
        String cashierToken = login("123456");

        // A cashier's own PIN is valid but not a manager's; "000000" matches nobody.
        for (String pin : List.of("123456", "000000")) {
            mockMvc.perform(post("/api/v1/auth/special-access")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                            .with(request -> {
                                request.setRemoteAddr("10.4.0.1"); // keep failures away from other tests' throttle
                                return request;
                            })
                            .contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"" + pin + "\",\"operation\":\"VOID\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.title").value("Special access required"));
        }
    }

    @Test
    void anApprovalMustNameOneKnownOperation() throws Exception {
        String cashierToken = login("123456");
        for (String body : List.of("{\"pin\":\"654321\"}", "{\"pin\":\"654321\",\"operation\":null}",
                "{\"pin\":\"654321\",\"operation\":\"PAYOUT\"}", "{\"pin\":\"654321\",\"operation\":\"void\"}",
                "{\"pin\":\"654321\",\"operation\":7}")) {
            mockMvc.perform(post("/api/v1/auth/special-access")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anApprovalWorksForItsOwnOperationOnly() throws Exception {
        String cashierToken = login("123456");
        long toVoid = placeOrder(4, 1);
        long toRefund = payNow(4, 1, "29.00");
        String voidToken = approval(cashierToken, "VOID");
        String refundToken = approval(cashierToken, "REFUND");
        String discountToken = approval(cashierToken, "DISCOUNT");

        // The wrong operation is refused, in the same words whatever is wrong, and spends nothing.
        for (String wrong : List.of(refundToken, discountToken)) {
            expectApprovalNeeded("/api/v1/orders/" + toVoid + "/void", wrong);
        }
        for (String wrong : List.of(voidToken, discountToken)) {
            expectApprovalNeeded("/api/v1/orders/" + toRefund + "/refund", wrong);
        }

        mockMvc.perform(post("/api/v1/orders/" + toVoid + "/void").header(HttpHeaders.AUTHORIZATION, "Bearer " + voidToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer left\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/orders/" + toRefund + "/refund").header(HttpHeaders.AUTHORIZATION, "Bearer " + refundToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong item\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void anApprovalWithoutAKnownOperationIsRefusedAndNotUsedUp() throws Exception {
        long id = placeOrder(4, 1);
        // An approval as the system issued them before operations existed, and unknown or odd spellings.
        for (Object operation : java.util.Arrays.asList(null, "PAYOUT", "void", "", 7)) {
            String token = approvalWithOperation(operation);
            expectApprovalNeeded("/api/v1/orders/" + id + "/void", token);
            expectApprovalNeeded("/api/v1/orders/" + id + "/refund", token);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM used_approval_token WHERE token_id = ?", Integer.class,
                    tokenId(token))).isZero();
        }
    }

    @Test
    void oneVoidApprovalIsUsedOnceEvenWhenTwoRequestsRaceForIt() throws Exception {
        String cashierToken = login("123456");
        long first = placeOrder(4, 1);
        long second = placeOrder(4, 1);
        String token = approval(cashierToken, "VOID");
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> outcomes = List.of(first, second).stream().map(id -> pool.submit(() -> {
                assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                return mockMvc.perform(post("/api/v1/orders/" + id + "/void").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Race\"}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            start.countDown();
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> outcome : outcomes) {
                statuses.add(outcome.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order WHERE id IN (?, ?) AND status = 'VOIDED'",
                Integer.class, first, second)).isEqualTo(1);
    }

    // ---- Search ----

    @Test
    void listsOneStatusTabNewestFirstWithItemSummaries() throws Exception {
        long older = payNow(1, 1, "16.00");
        long newer = payNow(4, 1, "29.00");
        placeOrder(1, 1);

        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].id").value(contains((int) newer, (int) older)))
                .andExpect(jsonPath("$.items[0].itemSummary").value("Tin Caramel"))
                .andExpect(jsonPath("$.totalItems").value(2));

        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").param("size", "1").with(cashier()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void searchesByOrderNumberOrReceiptCodePrefix() throws Exception {
        long id = payNow(4, 1, "29.00");
        String body = getOrder(id).andReturn().getResponse().getContentAsString();
        String orderNumber = JsonPath.read(body, "$.orderNumber");
        String receiptCode = JsonPath.read(body, "$.receiptCode");
        payNow(1, 1, "16.00");

        for (String q : List.of(orderNumber, orderNumber.substring(1), receiptCode.substring(0, 9).toLowerCase())) {
            mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").param("q", q).with(cashier()))
                    .andExpect(jsonPath("$.items[*].id").value(contains((int) id)));
        }
    }

    @Test
    void searchesByPartOfThePaymentReference() throws Exception {
        long id = idOf(perform(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"reference":"4432-0330","amount":29.00}]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated()));
        payNow(1, 1, "16.00");

        for (String q : List.of("0330", "4432-0330", "4432")) {
            mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").param("q", q).with(cashier()))
                    .andExpect(jsonPath("$.items[*].id").value(contains((int) id)));
        }
        // Too short to search references (and no such order number or receipt code).
        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").param("q", "-0").with(cashier()))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void aPlacedOrderCannotBePaidWhileTheRegisterIsClosed() throws Exception {
        long id = placeOrder(4, 1);
        TestRegister.close(jdbc);

        pay(id, payBody(UUID.randomUUID(), "29.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));
    }

    @Test
    void invalidListParametersAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("status", "LOST").with(cashier())).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/orders").param("status", "OPEN").param("size", "500").with(cashier()))
                .andExpect(status().isBadRequest());
    }

    // ---- Concurrency ----

    /** Paying and voiding the same open order at once must never leave a voided order with payments. */
    @RepeatedTest(3)
    void concurrentPayAndVoidLeaveAConsistentOrder() throws Exception {
        long id = placeOrder(4, 1);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> paying = pool.submit(() -> {
                start.await();
                return lifecycle.pay(id, new PayOrderRequest(UUID.randomUUID(),
                        List.of(new PaymentRequest(VISA, new BigDecimal("29.00"), "ref"))), new Terminal(cashierId, 1L));
            });
            Future<?> voiding = pool.submit(() -> {
                start.await();
                return lifecycle.voidOrder(id, "Customer left", new Terminal(managerId, 1L));
            });
            start.countDown();

            int succeeded = 0;
            for (Future<?> outcome : List.of(paying, voiding)) {
                try {
                    outcome.get();
                    succeeded++;
                } catch (java.util.concurrent.ExecutionException expected) {
                    // The loser sees either a conflict (overlapping) or a business rule (sequential).
                }
            }
            assertThat(succeeded).isEqualTo(1);
        }

        String status = jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, id);
        Integer payments = jdbc.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Integer.class, id);
        int expectedPayments = switch (status) {
            case "VOIDED" -> 0;
            case "CLOSED" -> 1;
            default -> throw new AssertionError("Unexpected status " + status);
        };
        assertThat(payments).isEqualTo(expectedPayments);
    }

    // ---- helpers ----

    private String approval(String cashierToken, String operation) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/special-access")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"654321\",\"operation\":\"" + operation + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    /** A manager's approval token as {@code operation} says (absent when null), signed like the real ones. */
    private String approvalWithOperation(Object operation) {
        Staff manager = staffRepository.findById(managerId).orElseThrow();
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("counterline-pos").id(UUID.randomUUID().toString())
                .subject(managerId.toString()).issuedAt(now).expiresAt(now.plusSeconds(300)).claim("name", manager.getFullName())
                .claim("role", "MANAGER").claim("outlet", 1L).claim("channel", "approval")
                .claim("sver", manager.getAuthSessionVersion()).claim("device", TestDevices.active(jdbc, cashierId, 1L));
        if (operation != null) {
            claims.claim("operation", operation);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private String tokenId(String token) {
        return jwtDecoder.decode(token).getId();
    }

    private void expectApprovalNeeded(String path, String token) throws Exception {
        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Check\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Manager approval needed"));
    }

    private long placeOrder(long itemId, int quantity) throws Exception {
        return idOf(perform(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":%d,"quantity":%d}],"payments":[]}"""
                .formatted(UUID.randomUUID(), itemId, quantity)).andExpect(status().isCreated()));
    }

    private long payNow(long itemId, int quantity, String amount) throws Exception {
        return idOf(perform(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":%d}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}"""
                .formatted(UUID.randomUUID(), itemId, quantity, amount)).andExpect(status().isCreated()));
    }

    private ResultActions pay(long orderId, String body) throws Exception {
        return perform(post("/api/v1/orders/" + orderId + "/payments"), cashier(), body);
    }

    private static String payBody(UUID requestId, String amount) {
        return """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}""".formatted(requestId, amount);
    }

    private ResultActions getOrder(long id) throws Exception {
        return mockMvc.perform(get("/api/v1/orders/" + id).with(cashier())).andExpect(status().isOk());
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  RequestPostProcessor auth, String json) throws Exception {
        return mockMvc.perform(request.with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Logs in on an iPad set up at outlet 1 by its manager (replacing the outlet's till, if any). */
    private String login(String pin) throws Exception {
        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Test' WHERE outlet_id = 1 AND reset_at IS NULL");
        String setUp = mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"654321\",\"outletId\":1}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String deviceKey = JsonPath.read(setUp, "$.deviceKey");
        String body = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + deviceKey + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("role", "STAFF"))
                .authorities(new SimpleGrantedAuthority("ROLE_STAFF"));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
