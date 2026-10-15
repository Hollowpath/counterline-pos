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

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Physical vouchers (issued on the dashboard, keyed in at the till, used once, expiring) and a
 * member's adjustments: points with a reason, and free items the staff forgot. Fixtures:
 * catalog-fixture.sql (item 1 Pouch Cheese 16.00, item 4 Tin Caramel 29.00) and
 * promotions-fixture.sql (item 25 Birthday Reward PC Caramel 12.00, promotion 5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/promotions-fixture.sql"})
class VouchersAndAdjustmentsIntegrationTest {

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
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer, voucher_batch, voucher RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
        // Born six months from now: never their birthday month.
        memberId = jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, date_of_birth, created_at, updated_at)
                VALUES ('Nur', 'Aisyah', ?, now() - interval '30 days', now()) RETURNING id
                """, Long.class, Date.valueOf(TODAY.plusMonths(6).withYear(1995).withDayOfMonth(15)));
    }

    // ---- Vouchers ----

    @Test
    void headOfficeIssuesVouchersAndEachIsUsedOnceAtTheTill() throws Exception {
        String created = call(post("/api/v1/dashboard/vouchers"), admin(),
                "{\"name\":\"RM10 Voucher\",\"value\":10,\"expiresOn\":\"" + TODAY.plusDays(30) + "\",\"quantity\":3}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.codes", hasSize(3)))
                .andExpect(jsonPath("$.batch.issued").value(3))
                .andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(created, "$.codes");
        String code = codes.getFirst();

        // Keyed in however it's typed.
        String typed = code.substring(0, 5).toLowerCase() + "-" + code.substring(5);
        mockMvc.perform(get("/api/v1/vouchers/" + typed).with(cashier()))
                .andExpect(jsonPath("$.status").value("UNUSED"))
                .andExpect(jsonPath("$.value").value(10.0))
                .andExpect(jsonPath("$.name").value("RM10 Voucher"));
        mockMvc.perform(get("/api/v1/vouchers/NOSUCHCODE").with(cashier())).andExpect(status().isNotFound());
        // A voucher batch's discount is never offered on the till's lists.
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].name").value(not(hasItem(containsString("RM10 Voucher (expires")))));

        // 29.00 − RM10 voucher = 19.00.
        checkout("PAY", 4, typed, "[]", "19.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(19.0))
                .andExpect(jsonPath("$.orderDiscounts[0].amount").value(10.0));
        checkout("PAY", 4, code, "[]", "19.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Voucher used"));
        quote(code).andExpect(jsonPath("$.problems", hasItem("Voucher used")))
                .andExpect(jsonPath("$.total").value(29.0));

        call(get("/api/v1/dashboard/vouchers"), admin(), null)
                .andExpect(jsonPath("$[0].issued").value(3))
                .andExpect(jsonPath("$[0].used").value(1));
        Integer batchId = JsonPath.read(created, "$.batch.id");
        call(get("/api/v1/dashboard/vouchers/" + batchId), admin(), null)
                .andExpect(jsonPath("$[?(@.code == '" + code + "')].status").value(contains("USED")));
        call(get("/api/v1/dashboard/vouchers"), manager(), null).andExpect(status().isForbidden());
    }

    @Test
    void aVoucherIsTheSalesOneDiscountExpiresAndComesBackWhenItsSaleIsVoidedOrRefunded() throws Exception {
        List<String> codes = JsonPath.read(call(post("/api/v1/dashboard/vouchers"), admin(),
                        "{\"name\":\"RM5 Voucher\",\"value\":5,\"expiresOn\":\"" + TODAY + "\",\"quantity\":2}")
                .andReturn().getResponse().getContentAsString(), "$.codes");

        Long discountId = jdbc.queryForObject("INSERT INTO discount (name, kind, value, scope, sort_order) "
                + "VALUES ('Voucher Test 10%', 'PERCENT', 10, 'ORDER', 50) "
                + "ON CONFLICT (name) DO UPDATE SET active = TRUE RETURNING id", Long.class);
        checkout("PAY", 4, codes.get(0), "[" + discountId + "]", "21.10").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("1 discount per sale"));

        // Placed with the voucher, then voided: the voucher can be used again.
        String placed = checkout("PLACE_ORDER", 4, codes.get(0), "[]", null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        checkout("PAY", 4, codes.get(0), "[]", "24.00").andExpect(status().isUnprocessableContent());
        Integer orderId = JsonPath.read(placed, "$.id");
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/void").with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Customer left\"}")).andExpect(status().isOk());
        String paid = checkout("PAY", 4, codes.get(0), "[]", "24.00").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Refunded: the whole sale is undone, so the voucher can be used again too.
        checkout("PAY", 4, codes.get(0), "[]", "24.00").andExpect(status().isUnprocessableContent());
        mockMvc.perform(post("/api/v1/orders/" + JsonPath.read(paid, "$.id") + "/refund").with(manager())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Wrong flavour\"}")).andExpect(status().isOk());
        checkout("PAY", 4, codes.get(0), "[]", "24.00").andExpect(status().isCreated());

        // Valid to the end of its expiry day, not after.
        jdbc.update("UPDATE voucher_batch SET expires_on = ?", Date.valueOf(TODAY.minusDays(1)));
        checkout("PAY", 4, codes.get(1), "[]", "24.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Voucher expired"));
        call(post("/api/v1/dashboard/vouchers"), admin(),
                "{\"name\":\"Old\",\"value\":5,\"expiresOn\":\"" + TODAY.minusDays(1) + "\",\"quantity\":1}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aVoucherSaleGivesNoFreeItemsButTheMemberFocForPoints() throws Exception {
        String code = JsonPath.<List<String>>read(call(post("/api/v1/dashboard/vouchers"), admin(),
                        "{\"name\":\"RM10 Gift Voucher\",\"value\":10,\"expiresOn\":\"" + TODAY.plusDays(30) + "\",\"quantity\":1}")
                .andReturn().getResponse().getContentAsString(), "$.codes").getFirst();
        // 3 Pouch Caramel (43.50): without a voucher, the member's Buy 3 Free 1 can is due...
        String threeBought = "{\"itemId\":2,\"quantity\":3}";
        memberQuote(null, threeBought)
                .andExpect(jsonPath("$.problems", hasItem(startsWith("Select"))));
        // ...with one, no free gift is due, and none may be given.
        memberQuote(code, threeBought)
                .andExpect(jsonPath("$.total").value(33.5))
                .andExpect(jsonPath("$.problems").isEmpty());
        memberQuote(code, threeBought + ",{\"itemId\":21,\"quantity\":1}")
                .andExpect(jsonPath("$.problems").value(contains("Discount on: remove B3 F1 BC Caramel")));

        // Points are still earned, and redeemed once they reach 7: the Member FOC item is allowed.
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), admin(),
                "{\"points\":7,\"reason\":\"Points from last month\"}").andExpect(status().isOk());
        memberQuote(code, threeBought)
                .andExpect(jsonPath("$.problems", hasItem("Select 1 Member FOC")));
        memberQuote(code, threeBought + ",{\"itemId\":23,\"quantity\":1}")
                .andExpect(jsonPath("$.problems").isEmpty())
                .andExpect(jsonPath("$.loyalty.after").value(3));
    }

    @Test
    void aDiscountThatCountsAsAVoucherGivesNoBuy3Free1ButTheFocOtherItemsAsUsual() throws Exception {
        // The till's RM10 Voucher: a paper voucher keyed in by hand, given on a line.
        Integer paperVoucher = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                        "{\"name\":\"Paper Voucher RM10\",\"kind\":\"AMOUNT\",\"value\":10,\"scope\":\"ITEM\",\"countsAsVoucher\":true}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.countsAsVoucher").value(true))
                .andReturn().getResponse().getContentAsString(), "$.id");
        // 12 Pouch Caramel (174.00, 164.00 after the voucher): no Buy 3 Free 1 cans are due, but the FOC
        // Other Items work as usual, so the member's tote bag still is (and the points still have to be
        // redeemed)...
        String twelveBought = "{\"itemId\":2,\"quantity\":12,\"discountIds\":[" + paperVoucher + "]}";
        memberQuote(null, twelveBought)
                .andExpect(jsonPath("$.total").value(164.0))
                .andExpect(jsonPath("$.problems", not(hasItem(containsString("Buy 3 Free 1 Promo")))))
                .andExpect(jsonPath("$.problems", hasItem(containsString("Spend RM150 Free Tote Bag"))))
                .andExpect(jsonPath("$.problems", hasItem(containsString("Member FOC"))));
        // ...and a Buy 3 Free 1 can may not be given; the tote and the recycle bag may.
        memberQuote(null, twelveBought + ",{\"itemId\":21,\"quantity\":1},{\"itemId\":28,\"quantity\":1},{\"itemId\":26,\"quantity\":1}")
                .andExpect(jsonPath("$.problems", hasItem("Discount on: remove B3 F1 BC Caramel")))
                .andExpect(jsonPath("$.problems", not(hasItem(containsString("Tote")))))
                .andExpect(jsonPath("$.problems", not(hasItem(containsString("Recycle Bag")))));
    }

    @Test
    void anyDiscountOnTheOrderOrALineGivesNoBuy3Free1Either() throws Exception {
        Integer tenPercent = JsonPath.read(call(post("/api/v1/dashboard/discounts"), admin(),
                        "{\"name\":\"Order Ten\",\"kind\":\"PERCENT\",\"value\":10,\"scope\":\"BOTH\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String threeBought = "{\"itemId\":2,\"quantity\":3}";
        String b3f1 = ",{\"itemId\":21,\"quantity\":1}";

        // On the order (the % tab): no free can is due, and none may be given.
        orderDiscountQuote(tenPercent, threeBought).andExpect(jsonPath("$.problems").isEmpty());
        orderDiscountQuote(tenPercent, threeBought + b3f1)
                .andExpect(jsonPath("$.problems").value(contains("Discount on: remove B3 F1 BC Caramel")));
        // The same with a discount on a line (the item's own discount).
        memberQuote(null, "{\"itemId\":2,\"quantity\":3,\"discountIds\":[" + tenPercent + "]}" + b3f1)
                .andExpect(jsonPath("$.problems").value(contains("Discount on: remove B3 F1 BC Caramel")));
        // The Member FOC items and the FOC Other Items are still allowed.
        memberQuote(null, "{\"itemId\":2,\"quantity\":3,\"discountIds\":[" + tenPercent + "]},{\"itemId\":26,\"quantity\":1}")
                .andExpect(jsonPath("$.problems", not(hasItem(containsString("remove")))));
    }

    // ---- Member adjustments ----

    @Test
    void staffAdjustAMembersPointsWithAReason() throws Exception {
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), admin(),
                "{\"points\":5,\"reason\":\"Points missed on receipt R12\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(5))
                .andExpect(jsonPath("$.entries[0].type").value("ADJUST"))
                .andExpect(jsonPath("$.entries[0].reason").value("Points missed on receipt R12"))
                .andExpect(jsonPath("$.entries[0].adjustedBy").value("Head Office"));
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), manager(), "{\"points\":-10,\"reason\":\"Typo\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Nur Aisyah has only 5 points"));
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), manager(), "{\"points\":-2,\"reason\":\"Typo\"}")
                .andExpect(jsonPath("$.balance").value(3))
                .andExpect(jsonPath("$.entries[0].adjustedBy").value("Test Manager"));
        call(post("/api/v1/dashboard/customers/" + memberId + "/points"), admin(), "{\"points\":3,\"reason\":\" \"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void aFreeItemTheStaffForgotIsGivenOnTheMembersNextSale() throws Exception {
        // Not their birthday month and only one item bought: the birthday can isn't allowed...
        sellBirthdayCan().andExpect(status().isUnprocessableContent());

        // ...until head office records that it was owed.
        call(get("/api/v1/dashboard/promotions"), manager(), null)
                .andExpect(jsonPath("$[*].name").value(hasItem("Birthday Reward")))
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Member Redemption"))));
        call(post("/api/v1/dashboard/customers/" + memberId + "/owed-items"), admin(),
                "{\"promotionId\":3,\"quantity\":1,\"reason\":\"Points item\"}")
                .andExpect(status().isBadRequest());
        call(post("/api/v1/dashboard/customers/" + memberId + "/owed-items"), admin(),
                "{\"promotionId\":5,\"quantity\":1,\"reason\":\"Forgot the birthday can on R40\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remaining").value(1));
        mockMvc.perform(get("/api/v1/customers/" + memberId + "/perks").with(cashier()))
                .andExpect(jsonPath("$.owedItems[0].promotion").value("Birthday Reward"))
                .andExpect(jsonPath("$.owedItems[0].items[0]").value("Birthday Reward PC Caramel"))
                .andExpect(jsonPath("$.owedItems[0].reason").value("Forgot the birthday can on R40"));

        Integer orderId = JsonPath.read(sellBirthdayCan().andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
        call(get("/api/v1/dashboard/customers/" + memberId + "/owed-items"), admin(), null).andExpect(jsonPath("$").isEmpty());
        // Given once.
        sellBirthdayCan().andExpect(status().isUnprocessableContent());

        // A refund of that sale: the can is owed again.
        call(post("/api/v1/orders/" + orderId + "/refund"), manager(), "{\"reason\":\"Damaged\"}").andExpect(status().isOk());
        call(get("/api/v1/dashboard/customers/" + memberId + "/owed-items"), admin(), null)
                .andExpect(jsonPath("$[0].remaining").value(1));
        sellBirthdayCan().andExpect(status().isCreated());
    }

    @Test
    void payingAPlacedOrderChecksItsVoucherHasNotExpiredSince() throws Exception {
        List<String> codes = JsonPath.read(call(post("/api/v1/dashboard/vouchers"), admin(),
                        "{\"name\":\"RM3 Voucher\",\"value\":3,\"expiresOn\":\"" + TODAY + "\",\"quantity\":1}")
                .andReturn().getResponse().getContentAsString(), "$.codes");
        Integer orderId = JsonPath.read(checkout("PLACE_ORDER", 1, codes.getFirst(), "[]", null)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        // The next day the voucher has expired.
        jdbc.update("UPDATE voucher_batch SET expires_on = ? WHERE name = 'RM3 Voucher'", java.sql.Date.valueOf(TODAY.minusDays(1)));

        call(post("/api/v1/orders/" + orderId + "/payments"), cashier(), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":13.00}]}"""
                .formatted(UUID.randomUUID()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Voucher expired"));
    }

    // ---- helpers ----

    /** One Pouch Cheese (16.00) and the birthday can, for the member. */
    private ResultActions sellBirthdayCan() throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","customerId":%d,"lines":[{"itemId":1,"quantity":1},{"itemId":25,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":16.00}]}""".formatted(UUID.randomUUID(), memberId)));
    }

    private ResultActions checkout(String mode, long itemId, String voucherCode, String discountIds, String amount) throws Exception {
        String payments = amount == null ? "[]" : "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]";
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"%s","lines":[{"itemId":%d,"quantity":1}],"discountIds":%s,
                 "voucherCode":"%s","payments":%s}""".formatted(UUID.randomUUID(), mode, itemId, discountIds, voucherCode, payments)));
    }

    private ResultActions quote(String voucherCode) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/quote").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],"voucherCode":"%s","payments":[]}"""
                .formatted(UUID.randomUUID(), voucherCode)));
    }

    /** The member's sale as quoted at the till, with {@code voucherCode} if not null. */
    private ResultActions orderDiscountQuote(Integer discountId, String lines) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/quote").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","customerId":%d,"lines":[%s],"discountIds":[%d],"payments":[]}"""
                .formatted(UUID.randomUUID(), memberId, lines, discountId)));
    }

    private ResultActions memberQuote(String voucherCode, String lines) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/quote").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","customerId":%d,"lines":[%s],"voucherCode":%s,"payments":[]}"""
                .formatted(UUID.randomUUID(), memberId, lines, voucherCode == null ? "null" : "\"" + voucherCode + "\"")));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String json) throws Exception {
        request.with(auth);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("name", "Test Cashier"));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(managerId.toString())
                        .claim("role", "MANAGER").claim("name", "Test Manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
