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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports over business days in the outlet's zone (Asia/Kuala_Lumpur, UTC+8). Orders are created
 * through the API, then their timestamps are pinned so day boundaries can be tested.
 * Fixture prices: item 1 Pouch Cheese 16.00 (category "Pouch"), item 4 Tin Caramel 29.00 ("Tin").
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class ReportApiIntegrationTest {

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
    void seedSales() throws Exception {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);

        // 12 Oct, 09:30 local: 2 × Pouch Cheese = 32.00 by Visa
        closedAt(sell(1, 2, "{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":32.00}"), "2026-10-12T01:30:00Z");
        // 12 Oct, 23:30 local: Tin Caramel 29.00 split Cash 20 + Visa 9
        closedAt(sell(4, 1, "{\"paymentMethodId\":6,\"amount\":20.00},{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":9.00}"),
                "2026-10-12T15:30:00Z");
        // 13 Oct, 00:30 local (still 12 Oct in UTC): not part of 12 Oct
        closedAt(sell(4, 1, "{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":29.00}"), "2026-10-12T16:30:00Z");
        // Sold on 11 Oct, refunded on 12 Oct 14:00 local: the refund belongs to 12 Oct
        long refunded = sell(1, 1, "{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":16.00}");
        closedAt(refunded, "2026-10-11T04:00:00Z");
        jdbc.update("UPDATE sale_order SET refund_reason = 'Damaged', refunded_by = ?, refunded_at = ? WHERE id = ?",
                cashierId, Timestamp.from(Instant.parse("2026-10-12T06:00:00Z")), refunded);
        // An unpaid (placed) order on 12 Oct is never a sale, but is listed as Open.
        String placed = mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":5}],"payments":[]}"""
                .formatted(UUID.randomUUID()))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        jdbc.update("UPDATE sale_order SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2026-10-12T05:00:00Z")), ((Number) JsonPath.read(placed, "$.id")).longValue());
    }

    @Test
    void salesSummaryForOneBusinessDay() throws Exception {
        report("sales-summary", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.grossSales").value(61.0))
                .andExpect(jsonPath("$.refundCount").value(1))
                .andExpect(jsonPath("$.refunds").value(16.0))
                .andExpect(jsonPath("$.netSales").value(45.0))
                .andExpect(jsonPath("$.paymentMethods[*].method").value(contains("Cash", "Visa")))
                .andExpect(jsonPath("$.paymentMethods[0].collected").value(20.0))
                .andExpect(jsonPath("$.paymentMethods[1].paymentCount").value(2))
                .andExpect(jsonPath("$.paymentMethods[1].collected").value(41.0))
                .andExpect(jsonPath("$.paymentMethods[1].refunded").value(16.0))
                .andExpect(jsonPath("$.paymentMethods[1].net").value(25.0))
                .andExpect(jsonPath("$.totalCollected").value(45.0))
                // Order Statuses and Dining Options, as on the printed Sales Summary.
                .andExpect(jsonPath("$.orderStatuses[*].status").value(contains("Paid", "Refunded", "Open")))
                .andExpect(jsonPath("$.orderStatuses[0].count").value(2))
                .andExpect(jsonPath("$.orderStatuses[0].amount").value(61.0))
                .andExpect(jsonPath("$.orderStatuses[1].amount").value(16.0))
                .andExpect(jsonPath("$.orderStatuses[2].amount").value(145.0))
                .andExpect(jsonPath("$.diningOptions[0].name").value("Retail"))
                .andExpect(jsonPath("$.diningOptions[0].paxCount").value(2))
                .andExpect(jsonPath("$.diningOptions[0].amount").value(45.0))
                .andExpect(jsonPath("$.diningOptions[0].perPaxAverage").value(22.5));
    }

    @Test
    void thePerPaxAverageIsCutToTheSenAsPrinted() throws Exception {
        // 14 Oct: 29.00 + 29.00 + 16.00 = 74.00 over 3 pax = 24.666…, printed as 24.66.
        closedAt(sell(4, 1, "{\"paymentMethodId\":6,\"amount\":29.00}"), "2026-10-14T02:00:00Z");
        closedAt(sell(4, 1, "{\"paymentMethodId\":6,\"amount\":29.00}"), "2026-10-14T03:00:00Z");
        closedAt(sell(1, 1, "{\"paymentMethodId\":6,\"amount\":16.00}"), "2026-10-14T04:00:00Z");
        report("sales-summary", "2026-10-14", "2026-10-14")
                .andExpect(jsonPath("$.diningOptions[0].perPaxAverage").value(24.66))
                .andExpect(jsonPath("$.orderStatuses[*].status").value(contains("Paid")));
    }

    @Test
    void thePaymentMethodsReportCountsPaymentsLessRefunds() throws Exception {
        report("payment-methods", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.methods[*].method").value(contains("Cash", "Visa")))
                .andExpect(jsonPath("$.methods[0].paymentCount").value(1))
                .andExpect(jsonPath("$.methods[0].net").value(20.0))
                .andExpect(jsonPath("$.methods[1].paymentCount").value(2))
                .andExpect(jsonPath("$.methods[1].net").value(25.0))
                .andExpect(jsonPath("$.total").value(45.0));
    }

    @Test
    void aDateRangeIncludesEveryDay() throws Exception {
        report("sales-summary", "2026-10-11", "2026-10-13")
                .andExpect(jsonPath("$.orderCount").value(4))
                .andExpect(jsonPath("$.grossSales").value(106.0))
                .andExpect(jsonPath("$.netSales").value(90.0));
    }

    @Test
    void hourlySalesUseTheOutletsLocalHourAndDeductRefundsWhenTheyWereMade() throws Exception {
        // Net 32 + (−16) + 29 = 45, the same as the Sales Summary's net for the day.
        report("hourly-sales", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.hours[*].hour").value(contains(9, 14, 23)))
                .andExpect(jsonPath("$.hours[0].sales").value(32.0))
                .andExpect(jsonPath("$.hours[0].net").value(32.0))
                .andExpect(jsonPath("$.hours[1].orderCount").value(0))
                .andExpect(jsonPath("$.hours[1].refunds").value(16.0))
                .andExpect(jsonPath("$.hours[1].net").value(-16.0))
                .andExpect(jsonPath("$.hours[2].net").value(29.0));
    }

    @Test
    void itemSalesLargestFirstAndCategorySalesByQuantityWithUnitPrices() throws Exception {
        report("item-sales", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.items[*].itemName").value(contains("Pouch Cheese", "Tin Caramel")))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].amount").value(32.0));

        // Moving an item to another category later does not rewrite history.
        jdbc.update("UPDATE item SET category_id = 2 WHERE id = 1");
        report("category-sales", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.categories[*].categoryName").value(contains("Pouch", "Tin")))
                .andExpect(jsonPath("$.categories[0].amount").value(32.0))
                .andExpect(jsonPath("$.categories[0].quantity").value(2))
                .andExpect(jsonPath("$.categories[0].items[0].itemName").value("Pouch Cheese"))
                .andExpect(jsonPath("$.categories[0].items[0].prices[0].label").value("Unit Price"))
                .andExpect(jsonPath("$.categories[0].items[0].prices[0].unitPrice").value(16.0))
                .andExpect(jsonPath("$.categories[0].items[0].prices[0].quantity").value(2))
                .andExpect(jsonPath("$.categories[0].items[0].prices[0].amount").value(32.0));

        // A price change splits the item by unit price, the larger quantity first.
        jdbc.update("UPDATE item SET price = 30.00 WHERE id = 4");
        closedAt(sell(4, 2, "{\"paymentMethodId\":6,\"amount\":60.00}"), "2026-10-12T08:00:00Z");
        report("category-sales", "2026-10-12", "2026-10-12")
                .andExpect(jsonPath("$.categories[*].categoryName").value(contains("Tin", "Pouch")))
                .andExpect(jsonPath("$.categories[0].quantity").value(3))
                .andExpect(jsonPath("$.categories[0].amount").value(89.0))
                .andExpect(jsonPath("$.categories[0].items[0].prices[*].unitPrice").value(contains(30.0, 29.0)));
    }

    @Test
    void invalidRangesAreRejected() throws Exception {
        report("sales-summary", "2026-10-12", "2026-10-11", status().isBadRequest());
        report("sales-summary", "2025-01-01", "2026-10-12", status().isBadRequest());
        mockMvc.perform(get("/api/v1/reports/sales-summary").param("from", "12/10/2026").param("to", "2026-10-12")
                .with(cashier())).andExpect(status().isBadRequest());
    }

    private long sell(long itemId, int quantity, String payments) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":%d}],"payments":[%s]}"""
                        .formatted(UUID.randomUUID(), itemId, quantity, payments)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void closedAt(long orderId, String instant) {
        Timestamp at = Timestamp.from(Instant.parse(instant));
        jdbc.update("UPDATE sale_order SET created_at = ?, closed_at = ? WHERE id = ?", at, at, orderId);
        jdbc.update("UPDATE payment SET created_at = ? WHERE order_id = ?", at, orderId);
    }

    private ResultActions report(String name, String from, String to) throws Exception {
        return report(name, from, to, status().isOk());
    }

    private ResultActions report(String name, String from, String to,
                                 org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        return mockMvc.perform(get("/api/v1/reports/" + name).param("from", from).param("to", to).with(cashier()))
                .andExpect(expected);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
