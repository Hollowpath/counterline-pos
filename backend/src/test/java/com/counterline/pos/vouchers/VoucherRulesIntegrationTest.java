package com.counterline.pos.vouchers;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules around voucher batches and the discounts behind them: who may change what, how a batch
 * is issued, how one voucher is used by two tills at once, how it is capped, and what editing a
 * placed order does with it. Fixture: catalog-fixture.sql (item 1 Pouch Cheese 16.00, item 4 Tin
 * Caramel 29.00).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class VoucherRulesIntegrationTest {

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
    private Long managerId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer, voucher_batch, voucher RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Rules Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Rules Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    // ---- The discount behind an issued batch ----

    @Test
    void anIssuedBatchesDiscountCannotBeEditedThroughTheDiscountsEndpoint() throws Exception {
        String name = "Locked " + unique();
        String code = issue(name, "10", TODAY.plusDays(30), 1).getFirst();
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT id, name, kind, value, scope, active FROM discount WHERE name LIKE ?", name + " (expires %");
        long id = ((Number) before.get("id")).longValue();

        List<String> attempts = List.of(
                // Renamed, and kept as a voucher discount.
                body("Renamed " + unique(), "AMOUNT", "10", "VOUCHER", true),
                // Revalued, as a normal discount.
                body(name, "AMOUNT", "500", "ORDER", true),
                // Exposed on the till's lists.
                body("Exposed " + unique(), "AMOUNT", "10", "BOTH", true),
                // Deactivated.
                body(name, "AMOUNT", "10", "VOUCHER", false),
                // Turned into a percentage.
                body(name, "PERCENT", "100", "ITEM", true));
        for (String attempt : attempts) {
            call(put("/api/v1/dashboard/discounts/" + id), admin(), attempt)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value("A voucher batch's discount can't be edited; it is managed with its vouchers"));
        }

        assertThat(jdbc.queryForMap("SELECT id, name, kind, value, scope, active FROM discount WHERE id = ?", id))
                .isEqualTo(before);
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].id").value(not(hasItem((int) id))));
        // It still takes off what it was issued for.
        checkout("PAY", 4, code, "19.00").andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(19.0));
        // Other discounts are still editable.
        Integer normal = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                body("Normal " + unique(), "AMOUNT", "2", "ORDER", true)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(put("/api/v1/dashboard/discounts/" + normal), admin(), body("Normal edited " + unique(), "AMOUNT", "3", "ORDER", true))
                .andExpect(status().isOk());
    }

    // ---- A placed order keeps the terms it was placed with ----

    @Test
    void deactivatingADiscountAfterPlacementChangesNeitherThePriceNorBlocksPayment() throws Exception {
        Integer ten = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                body("Retired Ten " + unique(), "PERCENT", "10", "ORDER", true)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        // 29.00 less 10% is 26.10.
        String placed = mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"discountIds":[%d],"payments":[]}"""
                .formatted(UUID.randomUUID(), ten)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.total").value(26.1))
                .andReturn().getResponse().getContentAsString();
        Integer orderId = JsonPath.read(placed, "$.id");

        call(put("/api/v1/dashboard/discounts/" + ten), admin(), body("Retired Ten again " + unique(), "PERCENT", "10", "ORDER", false))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // No longer offered on the till...
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(ten))));
        // ...but the placed order still pays at the price it was placed at.
        call(post("/api/v1/orders/" + orderId + "/payments"), cashier(), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":26.10}]}"""
                .formatted(UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.total").value(26.1))
                .andExpect(jsonPath("$.orderDiscounts[0].amount").value(2.9));
    }

    // ---- Issuing ----

    @Test
    void issuingChecksItsBoundaries() throws Exception {
        String ok = "Edge " + unique();
        // The smallest value and the largest, one and five hundred vouchers, an expiry today.
        batch(ok + " a", "0.01", TODAY, 1).andExpect(status().isCreated()).andExpect(jsonPath("$.codes", hasSize(1)));
        batch(ok + " b", "999999.99", TODAY, 1).andExpect(status().isCreated());
        String big = batch(ok + " c", "1", TODAY.plusDays(1), 500).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(big, "$.codes");
        assertThat(new HashSet<>(codes)).hasSize(500);
        assertThat(codes).allMatch(code -> code.matches("[A-HJ-NP-Z2-9]{10}"));
        batch(unique() + "x".repeat(42), "1", TODAY, 1).andExpect(status().isCreated());

        // Just outside.
        batch(ok + " d", "0", TODAY, 1).andExpect(status().isBadRequest());
        batch(ok + " d", "-1", TODAY, 1).andExpect(status().isBadRequest());
        batch(ok + " d", "0.001", TODAY, 1).andExpect(status().isBadRequest());
        batch(ok + " d", "1000000", TODAY, 1).andExpect(status().isBadRequest());
        batch(ok + " d", "1", TODAY, 0).andExpect(status().isBadRequest());
        batch(ok + " d", "1", TODAY, 501).andExpect(status().isBadRequest());
        batch(ok + " d", "1", TODAY.minusDays(1), 1).andExpect(status().isBadRequest());
        batch("", "1", TODAY, 1).andExpect(status().isBadRequest());
        batch("   ", "1", TODAY, 1).andExpect(status().isBadRequest());
        batch("x".repeat(51), "1", TODAY, 1).andExpect(status().isBadRequest());
        call(post("/api/v1/dashboard/vouchers"), admin(), "{\"name\":\"No expiry\",\"value\":1,\"quantity\":1}")
                .andExpect(status().isBadRequest());
        call(post("/api/v1/dashboard/vouchers"), admin(), "{\"name\":\"No value\",\"expiresOn\":\"" + TODAY + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest());
        // None of the refused ones left anything behind.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_batch WHERE name LIKE ?", Long.class, ok + " d")).isZero();
    }

    @Test
    void aBatchWithTheSameNameAndExpiryIsRefusedAndAnotherExpiryIsNot() throws Exception {
        String name = "Twice " + unique();
        batch(name, "5", TODAY.plusDays(10), 2).andExpect(status().isCreated());
        batch(name, "5", TODAY.plusDays(10), 2).andExpect(status().isConflict());
        // Spaces around the name make no difference, and a different value is still the same batch.
        batch("  " + name + "  ", "7", TODAY.plusDays(10), 2).andExpect(status().isConflict());
        // A different expiry is a different batch.
        batch(name, "5", TODAY.plusDays(11), 2).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher_batch WHERE name = ?", Long.class, name)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM voucher", Long.class)).isEqualTo(4L);
    }

    // ---- Using ----

    @Test
    void oneVoucherKeyedInOnTwoTillsAtOnceIsUsedOnce() throws Exception {
        String code = issue("Race " + unique(), "10", TODAY.plusDays(5), 1).getFirst();
        int tills = 4;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tills);
        try {
            List<Future<Integer>> sales = new ArrayList<>();
            for (int i = 0; i < tills; i++) {
                sales.add(pool.submit(() -> {
                    go.await();
                    return checkout("PAY", 4, code, "19.00").andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> sale : sales) {
                statuses.add(sale.get());
            }
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 422).hasSize(tills - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order WHERE status = 'CLOSED'", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(used_order_id) FROM voucher", Long.class)).isEqualTo(1L);
    }

    @Test
    void aVoucherWorthMoreThanTheOrderTakesOffOnlyTheOrder() throws Exception {
        String code = issue("Big " + unique(), "100", TODAY.plusDays(5), 1).getFirst();
        // Item 1 is 16.00.
        quote(code, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(16.0))
                .andExpect(jsonPath("$.discountTotal").value(16.0))
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.problems").isEmpty());
        checkout("PLACE_ORDER", 1, code, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.discountTotal").value(16.0))
                .andExpect(jsonPath("$.orderDiscounts[0].amount").value(16.0));
    }

    @Test
    void aVoucherCannotBeCombinedWithAnotherDiscountAndIsNotUsedByTheAttempt() throws Exception {
        String code = issue("Combine " + unique(), "10", TODAY.plusDays(5), 1).getFirst();
        Integer lineDiscount = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                body("Line Two " + unique(), "AMOUNT", "2", "ITEM", true)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        Integer orderDiscount = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                body("Order Two " + unique(), "AMOUNT", "2", "ORDER", true)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        String onLine = """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1,"discountIds":[%d]}],"voucherCode":"%s","payments":[]}"""
                .formatted(UUID.randomUUID(), lineDiscount, code);
        String onOrder = """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],"discountIds":[%d],"voucherCode":"%s","payments":[]}"""
                .formatted(UUID.randomUUID(), orderDiscount, code);
        for (String json : List.of(onLine, onOrder)) {
            call(post("/api/v1/orders/quote"), cashier(), json).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("1 discount per sale"));
            call(post("/api/v1/orders"), cashier(), json.replace("\"payments\":[]", "\"payments\":[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":19.00}]"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.detail").value("1 discount per sale"));
        }
        mockMvc.perform(get("/api/v1/vouchers/" + code).with(cashier())).andExpect(jsonPath("$.status").value("UNUSED"));
        checkout("PAY", 4, code, "19.00").andExpect(status().isCreated());
    }

    // ---- Editing a placed order ----

    @Test
    void editingAPlacedOrderCanKeepChangeOrRemoveItsVoucher() throws Exception {
        List<String> codes = issue("Edit " + unique(), "10", TODAY.plusDays(5), 3);
        String a = codes.get(0);
        String b = codes.get(1);
        String c = codes.get(2);
        Integer orderId = JsonPath.read(checkout("PLACE_ORDER", 4, a, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(19.0)).andReturn().getResponse().getContentAsString(), "$.id");
        assertThat(statusOf(a)).isEqualTo("USED");

        // Kept: still its own, and still used by the order.
        edit(orderId, a).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(19.0));
        assertThat(statusOf(a)).isEqualTo("USED");
        quoteEditing(orderId, a).andExpect(jsonPath("$.problems").isEmpty()).andExpect(jsonPath("$.total").value(19.0));

        // Changed to one a sale has already used: refused, and the order keeps the one it had.
        checkout("PAY", 1, c, "6.00").andExpect(status().isCreated());
        edit(orderId, c).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value("Voucher used"));
        assertThat(statusOf(a)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT used_order_id FROM voucher WHERE code = ?", Long.class, a)).isEqualTo(orderId.longValue());

        // Changed to an unused one: the old one is free again.
        edit(orderId, b).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(19.0));
        assertThat(statusOf(a)).isEqualTo("UNUSED");
        assertThat(statusOf(b)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT used_order_id FROM voucher WHERE code = ?", Long.class, b)).isEqualTo(orderId.longValue());

        // Removed: full price, and the voucher is free again.
        edit(orderId, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(29.0))
                .andExpect(jsonPath("$.orderDiscounts").isEmpty());
        assertThat(statusOf(b)).isEqualTo("UNUSED");
        assertThat(jdbc.queryForObject("SELECT count(used_order_id) FROM voucher WHERE code IN (?, ?)", Long.class, a, b)).isZero();
    }

    // ---- Refunding a sale that cost nothing ----

    @Test
    void aZeroTotalSaleIsRefundedOnceByAManagerAndGivesBackItsVoucher() throws Exception {
        String code = issue("Free " + unique(), "100", TODAY.plusDays(5), 1).getFirst();
        String paid = checkout("PAY", 1, code, null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.amountPaid").value(0.0))
                .andExpect(jsonPath("$.closedAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        Integer first = JsonPath.read(paid, "$.id");
        assertThat(statusOf(code)).isEqualTo("USED");

        // Refunds stay a manager's.
        refund(first, cashier()).andExpect(status().isForbidden());
        assertThat(statusOf(code)).isEqualTo("USED");

        refund(first, manager()).andExpect(status().isOk())
                .andExpect(jsonPath("$.refund.amount").value(0.0))
                .andExpect(jsonPath("$.refund.reason").value("Wrong flavour"));
        assertThat(statusOf(code)).isEqualTo("UNUSED");

        // The voucher goes on another sale; refunding the first one again changes nothing.
        Integer second = JsonPath.read(checkout("PAY", 1, code, null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        refund(first, manager()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("has already been refunded")));
        assertThat(statusOf(code)).isEqualTo("USED");
        assertThat(jdbc.queryForObject("SELECT used_order_id FROM voucher WHERE code = ?", Long.class, code)).isEqualTo(second.longValue());
    }

    @Test
    void aZeroTotalSaleThatRedeemedPointsGivesThemBackWhenRefunded() throws Exception {
        Long memberId = jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES ('Nur', 'Aisyah', ?, now() - interval '30 days', now()) RETURNING id
                """, Long.class, java.sql.Date.valueOf(TODAY.plusMonths(6).withYear(1995).withDayOfMonth(15)));
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), admin(),
                "{\"points\":7,\"reason\":\"Points from last month\"}").andExpect(status().isOk());

        // The member's free item costs 7 points and nothing else is bought: the sale totals nothing.
        String paid = call(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","customerId":%d,"lines":[{"itemId":23,"quantity":1}],"payments":[]}"""
                .formatted(UUID.randomUUID(), memberId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.loyalty.redeemed").value(7))
                .andReturn().getResponse().getContentAsString();
        assertThat(points(memberId)).isZero();

        refund(JsonPath.read(paid, "$.id"), manager()).andExpect(status().isOk());
        assertThat(points(memberId)).isEqualTo(7L);
    }

    @Test
    void closedAtIsWhenTheOrderWasPaidNotWhenItWasPlaced() throws Exception {
        String placed = call(post("/api/v1/orders"), cashier(), sale(4, null, "PLACE_ORDER", null)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(placed, "$.id");
        assertThat(JsonPath.<Object>read(placed, "$.closedAt")).isNull();
        jdbc.update("UPDATE sale_order SET created_at = now() - interval '2 days' WHERE id = ?", id);

        String paid = call(post("/api/v1/orders/" + id + "/payments"), cashier(), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":29.00}]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.time.Instant created = java.time.Instant.parse(JsonPath.read(paid, "$.createdAt"));
        java.time.Instant closed = java.time.Instant.parse(JsonPath.read(paid, "$.closedAt"));
        assertThat(closed).isAfter(created.plus(java.time.Duration.ofDays(1)));
        // Read back from the database, which keeps microseconds (rounded).
        String stored = JsonPath.read(call(get("/api/v1/orders/" + id), cashier(), null).andReturn().getResponse().getContentAsString(), "$.closedAt");
        assertThat(java.time.Duration.between(closed, java.time.Instant.parse(stored)).abs()).isLessThanOrEqualTo(java.time.Duration.ofNanos(1000));
    }

    // ---- helpers ----

    private ResultActions refund(Object orderId, RequestPostProcessor who) throws Exception {
        return call(post("/api/v1/orders/" + orderId + "/refund"), who, "{\"reason\":\"Wrong flavour\"}");
    }

    private long points(Long memberId) {
        return jdbc.queryForObject("SELECT coalesce(sum(points), 0) FROM loyalty_entry WHERE customer_id = ?", Long.class, memberId);
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String statusOf(String code) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/v1/vouchers/" + code).with(cashier()))
                .andReturn().getResponse().getContentAsString(), "$.status");
    }

    private static String body(String name, String kind, String value, String scope, boolean active) {
        return "{\"name\":\"%s\",\"kind\":\"%s\",\"value\":%s,\"scope\":\"%s\",\"active\":%s}".formatted(name, kind, value, scope, active);
    }

    private ResultActions batch(String name, String value, LocalDate expiresOn, int quantity) throws Exception {
        return call(post("/api/v1/dashboard/vouchers"), admin(),
                "{\"name\":\"%s\",\"value\":%s,\"expiresOn\":\"%s\",\"quantity\":%d}".formatted(name, value, expiresOn, quantity));
    }

    private List<String> issue(String name, String value, LocalDate expiresOn, int quantity) throws Exception {
        return JsonPath.read(batch(name, value, expiresOn, quantity).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.codes");
    }

    private String sale(int itemId, String voucherCode, String mode, String amount) {
        String payments = amount == null ? "[]" : "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]";
        return """
                {"clientRequestId":"%s","mode":"%s","lines":[{"itemId":%d,"quantity":1}],"voucherCode":%s,"payments":%s}"""
                .formatted(UUID.randomUUID(), mode, itemId, voucherCode == null ? "null" : "\"" + voucherCode + "\"", payments);
    }

    /** {@code amount} null: nothing paid (placing an order). */
    private ResultActions checkout(String mode, int itemId, String voucherCode, String amount) throws Exception {
        return call(post("/api/v1/orders"), cashier(), sale(itemId, voucherCode, mode, amount));
    }

    private ResultActions edit(Integer orderId, String voucherCode) throws Exception {
        return call(put("/api/v1/orders/" + orderId), cashier(), sale(4, voucherCode, "PLACE_ORDER", null));
    }

    private ResultActions quote(String voucherCode, int itemId) throws Exception {
        return call(post("/api/v1/orders/quote"), cashier(), sale(itemId, voucherCode, "PAY", null));
    }

    private ResultActions quoteEditing(Integer orderId, String voucherCode) throws Exception {
        return call(post("/api/v1/orders/quote?editing=" + orderId), cashier(), sale(4, voucherCode, "PAY", null));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String json) throws Exception {
        request.with(auth);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("name", "Rules Cashier"));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString())
                        .claim("role", "MANAGER").claim("name", "Rules Manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
