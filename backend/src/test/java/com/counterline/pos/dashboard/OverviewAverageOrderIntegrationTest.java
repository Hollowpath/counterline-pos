package com.counterline.pos.dashboard;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Overview's "Average order" is the Net sales figure beside it (after refunds) over the orders paid in
 * the period: the two always agree, and a refunded order brings the average down. Fixture: catalog-fixture.sql
 * (item 1 Pouch Cheese 16.00, item 4 Tin Caramel 29.00).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class OverviewAverageOrderIntegrationTest {

    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString();

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
    void aRegisterWithACashierAndAManager() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // also registers and orders
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    @Test
    void withoutRefundsItIsTheSalesOverTheOrders() throws Exception {
        sell(1, "16.00");
        sell(4, "29.00");

        overview().andExpect(jsonPath("$.current.netSales").value(45.0)).andExpect(jsonPath("$.current.orders").value(2))
                .andExpect(jsonPath("$.current.averageOrder").value(22.5));
    }

    @Test
    void aRefundedOrderBringsTheAverageDownWithTheNetSales() throws Exception {
        long small = sell(1, "16.00");
        sell(4, "29.00");
        refund(sell(4, "29.00"));
        refund(small);

        // Two of the three orders refunded: net RM29.00 over 3 orders, not the RM74.00 paid over 3.
        overview().andExpect(jsonPath("$.current.netSales").value(29.0)).andExpect(jsonPath("$.current.orders").value(3))
                .andExpect(jsonPath("$.current.averageOrder").value(9.67))
                .andExpect(jsonPath("$.current.refundCount").value(2)).andExpect(jsonPath("$.current.refunds").value(45.0));
    }

    @Test
    void oneRefundedOrderOfTwo() throws Exception {
        sell(1, "16.00");
        refund(sell(4, "29.00"));

        overview().andExpect(jsonPath("$.current.netSales").value(16.0)).andExpect(jsonPath("$.current.orders").value(2))
                .andExpect(jsonPath("$.current.averageOrder").value(8.0));
    }

    @Test
    void everyOrderRefundedLeavesAnAverageOfZero() throws Exception {
        refund(sell(1, "16.00"));
        refund(sell(4, "29.00"));

        overview().andExpect(jsonPath("$.current.netSales").value(0.0)).andExpect(jsonPath("$.current.orders").value(2))
                .andExpect(jsonPath("$.current.averageOrder").value(0.0));
    }

    @Test
    void theAverageIsRoundedToTheSenLikeEveryOtherAmount() throws Exception {
        sell(1, "16.00");
        sell(1, "16.00");
        sell(4, "29.00");
        // RM61.00 over 3 orders: 20.333... to 20.33.
        overview().andExpect(jsonPath("$.current.averageOrder").value(20.33));

        // RM45.00 net over 8 orders (five more sold and refunded, and the first RM16.00 one refunded too)
        // is 5.625: half up, to 5.63.
        for (int i = 0; i < 5; i++) {
            refund(sell(1, "16.00"));
        }
        refund(jdbcFirstOrder());
        overview().andExpect(jsonPath("$.current.orders").value(8)).andExpect(jsonPath("$.current.netSales").value(45.0))
                .andExpect(jsonPath("$.current.averageOrder").value(5.63));
    }

    @Test
    void noOrdersIsStillAnAverageOfZeroEvenWithARefundOfAnEarlierDay() throws Exception {
        overview().andExpect(jsonPath("$.current.orders").value(0)).andExpect(jsonPath("$.current.averageOrder").value(0.0));

        long old = sell(1, "16.00");
        jdbc.update("UPDATE sale_order SET created_at = now() - interval '3 days', closed_at = now() - interval '3 days' WHERE id = ?", old);
        refund(old);

        // Nothing was paid today, so no orders: Net sales is the refund taken out, the average has no orders to be over.
        overview().andExpect(jsonPath("$.current.orders").value(0)).andExpect(jsonPath("$.current.netSales").value(-16.0))
                .andExpect(jsonPath("$.current.averageOrder").value(0.0));
    }

    // ---- helpers ----

    /** The first order sold (a RM16.00 one): refunding it leaves RM45.00 of the RM61.00 net. */
    private long jdbcFirstOrder() {
        return jdbc.queryForObject("SELECT min(id) FROM sale_order", Long.class);
    }

    private ResultActions overview() throws Exception {
        return mockMvc.perform(get("/api/v1/dashboard/overview").param("from", TODAY).param("to", TODAY).param("outletId", "1").with(admin()))
                .andExpect(status().isOk());
    }

    /** One item sold and paid by card; returns the order id. */
    private long sell(long itemId, String amount) throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","lines":[{"itemId":%d,"quantity":1}],
                         "payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}""".formatted(UUID.randomUUID(), itemId, amount)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private void refund(long orderId) throws Exception {
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/refund").with(manager()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer returned it\"}")).andExpect(status().isOk());
    }

    private RequestPostProcessor admin() {
        return jwt().jwt(token -> token.claim("role", "ADMIN").subject("admin-1").claim("channel", "dashboard")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
