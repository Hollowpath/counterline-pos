package com.counterline.pos.reports;

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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the reports do with discounts and vouchers, refunds, zero-total sales and the edges of the
 * outlet's day (Asia/Kuala_Lumpur, UTC+8: 12 Oct runs from 11 Oct 16:00Z to 12 Oct 16:00Z).
 *
 * <p>The policy they pin down: the Sales Summary, Payment Methods and Hourly Sales are net of refunds
 * (a refund counts on the day it was made); the rankings of what was sold (Item Sales, Category
 * Sales, Discounts, Modifiers) are gross activity before refunds, so a refunded sale stays in them.
 * Fixtures: item 1 Pouch Cheese 16.00, item 4 Tin Caramel 29.00.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class ReportAccountingIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur"));
    private static final String VISA = "{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":%s}";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    private Long cashierId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer, voucher_batch, voucher RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Ledger Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    @Test
    void theSummaryTakesDiscountsAndVouchersOffGrossAndKeepsThemWhenTheSaleIsRefunded() throws Exception {
        long five = discount("Five Off " + unique(), "5");
        long discounted = sell(4, 1, ",\"discountIds\":[" + five + "]", VISA.formatted("24.00"));
        closedAt(discounted, "2026-10-12T02:00:00Z");
        long vouchered = sell(4, 1, ",\"voucherCode\":\"" + voucher("10") + "\"", VISA.formatted("19.00"));
        closedAt(vouchered, "2026-10-12T03:00:00Z");

        // 58.00 before discounts, less the 5.00 discount and the 10.00 voucher.
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.grossSales").value(58.0))
                .andExpect(jsonPath("$.discounts").value(15.0))
                .andExpect(jsonPath("$.refunds").value(0.0))
                .andExpect(jsonPath("$.netSales").value(43.0))
                .andExpect(jsonPath("$.totalCollected").value(43.0));
        report("discounts", "2026-10-12")
                .andExpect(jsonPath("$.discounts[*].amount").value(contains(10.0, 5.0)));

        // The voucher sale is refunded the same day: the 19.00 paid goes, and the discounts stay as given.
        refundedAt(vouchered, "2026-10-12T06:00:00Z");
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.grossSales").value(58.0))
                .andExpect(jsonPath("$.discounts").value(15.0))
                .andExpect(jsonPath("$.refundCount").value(1))
                .andExpect(jsonPath("$.refunds").value(19.0))
                .andExpect(jsonPath("$.netSales").value(24.0))
                .andExpect(jsonPath("$.totalCollected").value(24.0));
        report("discounts", "2026-10-12")
                .andExpect(jsonPath("$.discounts[*].amount").value(contains(10.0, 5.0)));
    }

    @Test
    void aSaleBelongsToTheLocalDayItWasPaidInAndARefundToTheLocalDayItWasMade() throws Exception {
        // Sales on both sides of local midnight: 00:00 is the new day, 23:59:59 the old one.
        closedAt(sell(1, 1, "", VISA.formatted("16.00")), "2026-10-11T16:00:00Z");
        closedAt(sell(1, 1, "", VISA.formatted("16.00")), "2026-10-12T15:59:59Z");
        closedAt(sell(1, 1, "", VISA.formatted("16.00")), "2026-10-12T16:00:00Z");
        // Three older sales, refunded at the same three instants.
        for (String at : List.of("2026-10-11T16:00:00Z", "2026-10-12T15:59:59Z", "2026-10-12T16:00:00Z")) {
            long old = sell(1, 1, "", VISA.formatted("16.00"));
            closedAt(old, "2026-10-01T02:00:00Z");
            refundedAt(old, at);
        }

        report("sales-summary", "2026-10-11")
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.refundCount").value(0));
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.grossSales").value(32.0))
                .andExpect(jsonPath("$.refundCount").value(2))
                .andExpect(jsonPath("$.refunds").value(32.0))
                .andExpect(jsonPath("$.netSales").value(0.0));
        report("sales-summary", "2026-10-13")
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.refundCount").value(1))
                .andExpect(jsonPath("$.refunds").value(16.0));
        // The same edges by the hour: 00:00 and 23:59 are the first and last hours of the 12th.
        report("hourly-sales", "2026-10-12")
                .andExpect(jsonPath("$.hours[*].hour").value(contains(0, 23)));
    }

    @Test
    void aRefundedSaleLeavesTheNetReportsButStaysInTheRankingsOfWhatWasSold() throws Exception {
        long five = discount("Five Off " + unique(), "5");
        long sold = sell(4, 1, ",\"discountIds\":[" + five + "]", VISA.formatted("24.00"));
        closedAt(sold, "2026-10-12T02:00:00Z");
        refundedAt(sold, "2026-10-12T06:00:00Z");
        // The smallest option fixture: one option picked twice on the sale's line.
        long line = jdbc.queryForObject("SELECT id FROM sale_order_line WHERE order_id = ?", Long.class, sold);
        long group = jdbc.queryForObject("INSERT INTO modifier_group (item_id, name, choose_exactly, sort_order) "
                + "VALUES (4, 'Gift Box', 2, 1) RETURNING id", Long.class);
        long option = jdbc.queryForObject("INSERT INTO modifier_option (group_id, name, price, sort_order) "
                + "VALUES (?, 'Tomato', 0, 1) RETURNING id", Long.class, group);
        jdbc.update("INSERT INTO sale_order_line_modifier (line_id, option_id, group_name, option_name, unit_price, quantity) "
                + "VALUES (?, ?, 'Gift Box', 'Tomato', 0, 2)", line, option);

        // Net of the refund.
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.grossSales").value(29.0))
                .andExpect(jsonPath("$.refunds").value(24.0))
                .andExpect(jsonPath("$.netSales").value(0.0))
                .andExpect(jsonPath("$.totalCollected").value(0.0));
        report("payment-methods", "2026-10-12").andExpect(jsonPath("$.total").value(0.0));
        report("hourly-sales", "2026-10-12")
                .andExpect(jsonPath("$.hours[?(@.hour == 10)].net").value(contains(24.0)))
                .andExpect(jsonPath("$.hours[?(@.hour == 14)].net").value(contains(-24.0)));

        // Gross activity: what was sold, before the discount and before the refund.
        report("item-sales", "2026-10-12")
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].itemName").value("Tin Caramel"))
                .andExpect(jsonPath("$.items[0].quantity").value(1))
                .andExpect(jsonPath("$.items[0].amount").value(29.0));
        report("category-sales", "2026-10-12")
                .andExpect(jsonPath("$.categories[0].quantity").value(1))
                .andExpect(jsonPath("$.categories[0].amount").value(29.0));
        report("discounts", "2026-10-12")
                .andExpect(jsonPath("$.discounts", hasSize(1)))
                .andExpect(jsonPath("$.discounts[0].amount").value(5.0));
        report("modifiers", "2026-10-12")
                .andExpect(jsonPath("$.options", hasSize(1)))
                .andExpect(jsonPath("$.options[0].groupName").value("Gift Box"))
                .andExpect(jsonPath("$.options[0].optionName").value("Tomato"))
                .andExpect(jsonPath("$.options[0].quantity").value(2))
                .andExpect(jsonPath("$.options[0].amount").value(0.0));
        // Not on another day: it follows the sale's payment date, not the refund's.
        report("modifiers", "2026-10-13").andExpect(jsonPath("$.options", hasSize(0)));
    }

    @Test
    void aZeroTotalSaleIsAnOrderInTheCountAndTheAverageAndARefundOfNothing() throws Exception {
        long free = sell(1, 1, ",\"voucherCode\":\"" + voucher("100") + "\"", "");
        closedAt(free, "2026-10-12T02:00:00Z");
        closedAt(sell(4, 1, "", VISA.formatted("29.00")), "2026-10-12T03:00:00Z");

        // 45.00 gross, 16.00 taken off by the voucher (it covers only the sale), 29.00 paid over two orders.
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.grossSales").value(45.0))
                .andExpect(jsonPath("$.discounts").value(16.0))
                .andExpect(jsonPath("$.netSales").value(29.0))
                .andExpect(jsonPath("$.orderStatuses[0].count").value(2))
                .andExpect(jsonPath("$.orderStatuses[0].amount").value(29.0))
                .andExpect(jsonPath("$.diningOptions[0].paxCount").value(2))
                .andExpect(jsonPath("$.diningOptions[0].perPaxAverage").value(14.5));

        // Refunded, it is a refund of RM0.00: counted, but nothing comes off the money.
        refundedAt(free, "2026-10-12T06:00:00Z");
        report("sales-summary", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.refundCount").value(1))
                .andExpect(jsonPath("$.refunds").value(0.0))
                .andExpect(jsonPath("$.netSales").value(29.0))
                .andExpect(jsonPath("$.orderStatuses[*].status").value(contains("Paid", "Refunded")))
                .andExpect(jsonPath("$.orderStatuses[1].count").value(1))
                .andExpect(jsonPath("$.orderStatuses[1].amount").value(0.0))
                .andExpect(jsonPath("$.diningOptions[0].perPaxAverage").value(14.5));
    }

    @Test
    void anOrderPlacedBeforeMidnightAndPaidAfterIsReportedAndDatedOnTheDayItWasPaid() throws Exception {
        String placed = call(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"payments":[]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long orderId = ((Number) JsonPath.read(placed, "$.id")).longValue();
        assertThat(JsonPath.<Object>read(placed, "$.closedAt")).isNull();

        call(post("/api/v1/orders/" + orderId + "/payments"), cashier(), """
                {"clientRequestId":"%s","payments":[%s]}""".formatted(UUID.randomUUID(), VISA.formatted("29.00")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        // Placed 23:50 on the 12th (15:50Z), paid 00:10 on the 13th (16:10Z).
        jdbc.update("UPDATE sale_order SET created_at = ?, closed_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-10-12T15:50:00Z")), Timestamp.from(Instant.parse("2026-10-12T16:10:00Z")), orderId);
        jdbc.update("UPDATE payment SET created_at = ? WHERE order_id = ?", Timestamp.from(Instant.parse("2026-10-12T16:10:00Z")), orderId);

        call(get("/api/v1/orders/" + orderId), cashier(), null)
                .andExpect(jsonPath("$.createdAt").value("2026-10-12T15:50:00Z"))
                .andExpect(jsonPath("$.closedAt").value("2026-10-12T16:10:00Z"));
        report("sales-summary", "2026-10-12").andExpect(jsonPath("$.orderCount").value(0));
        report("sales-summary", "2026-10-13").andExpect(jsonPath("$.orderCount").value(1)).andExpect(jsonPath("$.grossSales").value(29.0));
    }

    // ---- helpers ----

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A discount taking {@code amount} off an order; its id. */
    private long discount(String name, String amount) throws Exception {
        return ((Number) JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                "{\"name\":\"%s\",\"kind\":\"AMOUNT\",\"value\":%s,\"scope\":\"ORDER\"}".formatted(name, amount))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** A one-voucher batch worth {@code value}; its code. */
    private String voucher(String value) throws Exception {
        return JsonPath.<List<String>>read(call(post("/api/v1/dashboard/vouchers"), admin(),
                "{\"name\":\"Ledger %s\",\"value\":%s,\"expiresOn\":\"%s\",\"quantity\":1}".formatted(unique(), value, TODAY.plusDays(30)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.codes").getFirst();
    }

    /** Pays one line at once; {@code payments} empty for a sale that costs nothing. */
    private long sell(long itemId, int quantity, String extra, String payments) throws Exception {
        String body = call(post("/api/v1/orders"), cashier(), """
                {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":%d}]%s,"payments":[%s]}"""
                .formatted(UUID.randomUUID(), itemId, quantity, extra, payments)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void closedAt(long orderId, String instant) {
        Timestamp at = Timestamp.from(Instant.parse(instant));
        jdbc.update("UPDATE sale_order SET created_at = ?, closed_at = ? WHERE id = ?", at, at, orderId);
        jdbc.update("UPDATE payment SET created_at = ? WHERE order_id = ?", at, orderId);
    }

    private void refundedAt(long orderId, String instant) {
        jdbc.update("UPDATE sale_order SET refund_reason = 'Test', refunded_by = ?, refunded_at = ? WHERE id = ?",
                cashierId, Timestamp.from(Instant.parse(instant)), orderId);
    }

    private ResultActions report(String name, String day) throws Exception {
        return mockMvc.perform(get("/api/v1/reports/" + name).param("from", day).param("to", day).with(cashier()))
                .andExpect(status().isOk());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String json) throws Exception {
        request.with(auth);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("name", "Ledger Cashier"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
