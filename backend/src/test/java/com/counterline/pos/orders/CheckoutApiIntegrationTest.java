package com.counterline.pos.orders;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.orders.CheckoutService.CheckoutResult;
import com.counterline.pos.orders.OrderDtos.CheckoutMode;
import com.counterline.pos.orders.OrderDtos.CheckoutRequest;
import com.counterline.pos.orders.OrderDtos.LineRequest;
import com.counterline.pos.orders.OrderDtos.PaymentRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout against PostgreSQL. Catalogue fixture prices: item 1 Pouch Cheese 16.00,
 * item 3 inactive, item 4 Tin Caramel 29.00. Payment methods come from V3 (1 DuitNow QR, 2 Visa)
 * and V8 (6 Cash). The register is open unless a test closes it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class CheckoutApiIntegrationTest {

    private static final long DUITNOW = 1;
    private static final long VISA = 2;
    private static final long CASH = 6;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    CheckoutService checkoutService;

    private Long cashierId;

    @BeforeEach
    void createCashier() {
        jdbc.execute("TRUNCATE login_throttle, staff RESTART IDENTITY CASCADE"); // cascades to orders and payments
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    @Test
    void createsAClosedOrderPricedFromTheCatalogue() throws Exception {
        checkout("""
                {"clientRequestId":"%s","note":" Gift wrap ",
                 "lines":[{"itemId":1,"quantity":2},{"itemId":4,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"amount":61.00,"reference":"4389-0340"}]}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/orders/")))
                .andExpect(jsonPath("$.orderNumber").value(matchesPattern("R\\d+")))
                .andExpect(jsonPath("$.receiptCode").value(matchesPattern("\\d{6}[A-Z]{7}")))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.total").value(61.0))
                .andExpect(jsonPath("$.note").value("Gift wrap"))
                .andExpect(jsonPath("$.staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.lines[*].itemName").value(contains("Pouch Cheese", "Tin Caramel")))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(32.0))
                .andExpect(jsonPath("$.payments[0].method").value("Visa"))
                .andExpect(jsonPath("$.payments[0].reference").value("4389-0340"));

        assertRowCounts(1, 2, 1);
    }

    @Test
    void aSaleBeyondWhatTheMoneyColumnsHoldIsRefusedAsAClientErrorAndOneAtTheLimitIsRecorded() throws Exception {
        // A custom amount takes 8 digits and a quantity up to 999; lines and orders hold 10 digits before the cents.
        // 99,999,999.99 x 101 = 10,099,999,998.99 does not fit; x 100 = 9,999,999,999.00 does.
        checkout(customAmountSale("[{\"customAmount\":99999999.99,\"quantity\":101}]", "9999999999.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The sale is too large"));
        // Each line fits, the order does not: 2 x (99,999,999.99 x 60) = 11,999,999,998.80.
        checkout(customAmountSale("[{\"customAmount\":99999999.99,\"quantity\":60},{\"customAmount\":99999999.99,\"quantity\":60}]",
                "9999999999.00"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("The sale is too large"));
        assertRowCounts(0, 0, 0);

        checkout(customAmountSale("[{\"customAmount\":99999999.99,\"quantity\":100}]", "9999999999.00"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(9999999999.00));
        assertRowCounts(1, 1, 1);
    }

    private static String customAmountSale(String lines, String cash) {
        return "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"lines\":" + lines
                + ",\"payments\":[{\"paymentMethodId\":6,\"amount\":" + cash + "}]}";
    }

    @Test
    void acceptsOneCashlessPaymentPlusCashAndRecordsTheChange() throws Exception {
        checkout(request(List.of(new LineRequest(4L, 2)),
                List.of(new PaymentRequest(DUITNOW, new BigDecimal("40.00"), "0716"),
                        new PaymentRequest(CASH, new BigDecimal("18.00"), null, new BigDecimal("20.00")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountPaid").value(58.0))
                .andExpect(jsonPath("$.payments[*].method").value(contains("DuitNow QR", "Cash")))
                .andExpect(jsonPath("$.payments[1].kind").value("CASH"))
                .andExpect(jsonPath("$.payments[1].cashTendered").value(20.0))
                .andExpect(jsonPath("$.payments[1].change").value(2.0));
    }

    @Test
    void twoCashlessMethodsOrAMissingReferenceAreRefused() throws Exception {
        checkout(request(List.of(new LineRequest(4L, 2)),
                List.of(new PaymentRequest(DUITNOW, new BigDecimal("40.00"), "0716"),
                        new PaymentRequest(VISA, new BigDecimal("18.00"), "4389-0340"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("A sale takes one cashless payment; the rest can be paid in cash"));
        checkout(request(List.of(new LineRequest(4L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("29.00"), null))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Visa reference needed"));

        assertRowCounts(0, 0, 0);
    }

    @Test
    void payingAndPlacingAreRefusedWhileTheRegisterIsClosed() throws Exception {
        TestRegister.close(jdbc);

        checkout(request(List.of(new LineRequest(4L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("29.00"), "ref"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));
        assertRowCounts(0, 0, 0);

        checkout("""
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"payments":[]}
                """.formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));
        assertRowCounts(0, 0, 0);
    }

    @Test
    void paymentsThatDoNotCoverTheTotalAreRejectedAndNothingIsSaved() throws Exception {
        checkout(request(List.of(new LineRequest(4L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("20.00"), "ref"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Payments (RM20.00) must equal the order total (RM29.00)"));

        assertRowCounts(0, 0, 0);
    }

    @Test
    void anItemThatIsNoLongerSoldRollsBackTheWholeSale() throws Exception {
        checkout(request(List.of(new LineRequest(1L, 1), new LineRequest(3L, 1)),
                List.of(new PaymentRequest(VISA, new BigDecimal("26.00"), "ref"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Item 3 is no longer available"));

        assertRowCounts(0, 0, 0);
    }

    @Test
    void unknownPaymentMethodIsRejected() throws Exception {
        checkout(request(List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(999L, new BigDecimal("16.00"), null))))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void freeItemsNeedNoPayment() throws Exception {
        jdbc.update("INSERT INTO item (category_id, name, price, sort_order) VALUES (1, 'Free Sample', 0, 9)");
        Long freeItemId = jdbc.queryForObject("SELECT id FROM item WHERE name = 'Free Sample'", Long.class);

        checkout(request(List.of(new LineRequest(freeItemId, 2)), List.of()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.total").value(0.0));
    }

    @Test
    void malformedRequestsAreRejectedBeforeAnyBusinessLogic() throws Exception {
        checkout(request(List.of(), List.of())).andExpect(status().isBadRequest());
        checkout(request(List.of(new LineRequest(1L, 0)), List.of())).andExpect(status().isBadRequest());
        checkout(request(List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("16.001"), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void linesKeepThePriceTheyWereSoldAt() throws Exception {
        String body = checkout(request(List.of(new LineRequest(4L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("29.00"), "ref"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Integer orderId = JsonPath.read(body, "$.id");

        jdbc.update("UPDATE item SET price = 35.00, name = 'Tin Caramel (new)' WHERE id = 4");

        mockMvc.perform(get("/api/v1/orders/" + orderId).with(cashierToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].itemName").value("Tin Caramel"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(29.0))
                .andExpect(jsonPath("$.total").value(29.0));
    }

    @Test
    void retryingWithTheSameClientRequestIdReturnsTheOriginalOrder() throws Exception {
        String json = request(List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("16.00"), "ref")));

        String first = checkout(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = checkout(json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((Integer) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertRowCounts(1, 1, 1);
    }

    @Test
    void concurrentDuplicateSubmissionsRecordTheSaleOnce() throws Exception {
        CheckoutRequest request = new CheckoutRequest(UUID.randomUUID(), CheckoutMode.PAY, null, null,
                List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("16.00"), "ref")));
        CountDownLatch start = new CountDownLatch(1);
        Callable<CheckoutResult> submit = () -> {
            start.await();
            return checkoutService.checkout(request, new Terminal(cashierId, 1L));
        };

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<CheckoutResult> a = pool.submit(submit);
            Future<CheckoutResult> b = pool.submit(submit);
            start.countDown();

            assertThat(a.get().order().id()).isEqualTo(b.get().order().id());
            assertThat(List.of(a.get().created(), b.get().created())).containsExactlyInAnyOrder(true, false);
        }
        assertRowCounts(1, 1, 1);
    }

    @Test
    void orderNumbersIncreaseWithEachSale() throws Exception {
        String one = request(List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("16.00"), "ref")));
        String two = request(List.of(new LineRequest(1L, 1)), List.of(new PaymentRequest(VISA, new BigDecimal("16.00"), "ref")));

        int first = orderNumberOf(checkout(one));
        int second = orderNumberOf(checkout(two));

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void paymentMethodsAreListedInDisplayOrder() throws Exception {
        mockMvc.perform(get("/api/v1/payment-methods").with(cashierToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(contains("Cash", "DuitNow QR", "Visa", "Mastercard", "MyDebit")));
    }

    @Test
    void unknownOrderIs404() throws Exception {
        mockMvc.perform(get("/api/v1/orders/999999").with(cashierToken()))
                .andExpect(status().isNotFound());
    }

    private ResultActions checkout(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/orders")
                .with(cashierToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor cashierToken() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private static String request(List<LineRequest> lines, List<PaymentRequest> payments) {
        StringBuilder json = new StringBuilder("{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"lines\":[");
        json.append(String.join(",", lines.stream()
                .map(line -> "{\"itemId\":" + line.itemId() + ",\"quantity\":" + line.quantity() + "}").toList()));
        json.append("],\"payments\":[");
        json.append(String.join(",", payments.stream()
                .map(p -> "{\"paymentMethodId\":" + p.paymentMethodId() + ",\"amount\":" + p.amount().toPlainString()
                        + (p.reference() == null ? "" : ",\"reference\":\"" + p.reference() + "\"")
                        + (p.cashTendered() == null ? "" : ",\"cashTendered\":" + p.cashTendered().toPlainString())
                        + "}").toList()));
        return json.append("]}").toString();
    }

    private static int orderNumberOf(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Integer.parseInt(JsonPath.<String>read(body, "$.orderNumber").substring(1));
    }

    private void assertRowCounts(int orders, int lines, int payments) {
        assertThat(count("sale_order")).as("orders").isEqualTo(orders);
        assertThat(count("sale_order_line")).as("order lines").isEqualTo(lines);
        assertThat(count("payment")).as("payments").isEqualTo(payments);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
