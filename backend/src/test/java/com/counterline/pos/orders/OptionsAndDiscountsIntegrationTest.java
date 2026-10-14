package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.TokenService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
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

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Item options, line/order discounts and custom amounts end to end: catalogue → checkout →
 * stored amounts (which the database check constraints also verify) → reports.
 * Fixtures: catalog-fixture.sql then options-fixture.sql.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/options-fixture.sql"})
class OptionsAndDiscountsIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    TokenService tokenService;

    private Long cashierId;

    @BeforeEach
    void createCashier() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    // ---- Catalogue ----

    @Test
    void itemsWithOptionsOpenTheItemDialog() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/categories/2/items").with(cashier()))
                .andExpect(jsonPath("$.items[?(@.name == 'Gift Box')].hasOptions").value(contains(true)))
                .andExpect(jsonPath("$.items[?(@.name == 'Tin Caramel')].hasOptions").value(contains(false)));

        mockMvc.perform(get("/api/v1/catalog/items/7/options").with(cashier()))
                .andExpect(jsonPath("$.groups[0].name").value("Flavours"))
                .andExpect(jsonPath("$.groups[0].chooseExactly").value(3))
                .andExpect(jsonPath("$.groups[0].options[*].name").value(contains("Caramel", "Cheese", "Matcha")));
    }

    @Test
    void discountsAreOfferedWhereTheirScopeAllows() throws Exception {
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ITEM").with(cashier()))
                .andExpect(jsonPath("$[*].name").value(contains("Staff 30%", "Voucher RM10", "Comp 100%")));
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].name").value(contains("Staff 30%", "Order 10%", "Comp 100%", "Marketing Dept 100%")))
                .andExpect(jsonPath("$[3].requiresApproval").value(true));
    }

    @Test
    void aDepartmentCompNeedsAManagersApprovalToBePaid() throws Exception {
        Staff manager = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L));

        payNothing("[5]", null)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("A manager must approve this discount"));
        payNothing("[5]", "not-a-token").andExpect(status().isForbidden());
        // A cashier's own token is not an approval.
        String cashierToken = tokenService.issue(staffRepository.findById(cashierId).orElseThrow(), 1L).value();
        payNothing("[5]", cashierToken).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order", Integer.class)).isZero();

        // Placing the order is never refused; the approval is asked for when it is paid.
        checkout("[{\"itemId\":1,\"quantity\":1}]", "[5]", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"));

        // The manager's special-access token approves it; the sale stays the cashier's.
        String approval = tokenService.issue(manager, 1L, Duration.ofMinutes(5)).value();
        payNothing("[5]", approval)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.orderDiscounts[0].detail").value("Approved by Test Manager"));
        assertThat(jdbc.queryForObject("SELECT approved_by FROM sale_order_discount WHERE approved_by IS NOT NULL", Long.class))
                .isEqualTo(manager.getId());
    }

    /** Pays a Pouch Cheese with the order's discounts, nothing to pay when they take it all. */
    private ResultActions payNothing(String orderDiscounts, String approvalToken) throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"PAY","lines":[{"itemId":1,"quantity":1}],"discountIds":%s,"payments":[],
                 "approvalToken":%s}"""
                .formatted(UUID.randomUUID(), orderDiscounts, approvalToken == null ? "null" : "\"" + approvalToken + "\"")));
    }

    // ---- Checkout ----

    /**
     * Gift Box 70.00 + 2 × Matcha (+2.00 each) = 74.00 a unit; × 2 = 148.00.
     * Line: Staff 30% (44.40) → 103.60. Plus Pouch Cheese 16.00 → 119.60 (subtotal 164.00).
     */
    @Test
    void pricesOptionsAndALineDiscountOnTheServer() throws Exception {
        checkout("""
                [{"itemId":7,"quantity":2,"note":"Ribbon","discountIds":[1],
                  "modifiers":[{"optionId":1,"quantity":1},{"optionId":3,"quantity":2}]},
                 {"itemId":1,"quantity":1}]""", "[]", "119.60")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtotal").value(164.0))
                .andExpect(jsonPath("$.discountTotal").value(44.4))
                .andExpect(jsonPath("$.total").value(119.6))
                .andExpect(jsonPath("$.lines[0].modifierUnitPrice").value(4.0))
                .andExpect(jsonPath("$.lines[0].lineSubtotal").value(148.0))
                .andExpect(jsonPath("$.lines[0].discounts[*].name").value(contains("Staff 30%")))
                .andExpect(jsonPath("$.lines[0].discounts[0].amount").value(44.4))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(103.6))
                .andExpect(jsonPath("$.lines[0].note").value("Ribbon"))
                .andExpect(jsonPath("$.lines[0].modifiers[*].optionName").value(contains("Caramel", "Matcha")))
                .andExpect(jsonPath("$.orderDiscounts").isEmpty());
    }

    /** Order 10% of 164.00 = 16.40 → 147.60. */
    @Test
    void anOrderDiscountAppliesToTheWholeSale() throws Exception {
        checkout("""
                [{"itemId":7,"quantity":2,"modifiers":[{"optionId":1,"quantity":1},{"optionId":3,"quantity":2}]},
                 {"itemId":1,"quantity":1}]""", "[3]", "147.60")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderDiscounts[0].name").value("Order 10%"))
                .andExpect(jsonPath("$.orderDiscounts[0].amount").value(16.4))
                .andExpect(jsonPath("$.total").value(147.6));
    }

    @Test
    void aSaleTakesOneDiscountAndItNeverExceedsTheLine() throws Exception {
        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[4,1]}]", "[]", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Only one discount can be applied to a sale"));
        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[1]},{\"itemId\":4,\"quantity\":1}]", "[3]", null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Only one discount can be applied to a sale"));

        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[2]}]", "[]", null) // RM10 voucher on a 16.00 line
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(6.0));
        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[4]}]", "[]", null) // Comp 100%
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0.0));
    }

    @Test
    void optionGroupsMustBeSatisfiedExactly() throws Exception {
        checkout("[{\"itemId\":7,\"quantity\":1,\"modifiers\":[{\"optionId\":1,\"quantity\":2}]}]", "[]", "70.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Choose exactly 3 for Flavours (2 chosen)"));
        checkout("[{\"itemId\":7,\"quantity\":1}]", "[]", "70.00")
                .andExpect(status().isUnprocessableContent());
        checkout("[{\"itemId\":7,\"quantity\":1,\"modifiers\":[{\"optionId\":4,\"quantity\":3}]}]", "[]", "70.00")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Option 4 is not available for Gift Box"));
        checkout("[{\"itemId\":1,\"quantity\":1,\"modifiers\":[{\"optionId\":1,\"quantity\":1}]}]", "[]", "16.00")
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_order", Integer.class)).isZero();
    }

    @Test
    void discountsMustBeAllowedWhereTheyAreUsedAndUsedOnce() throws Exception {
        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[3]}]", "[]", null) // Order 10% is order-only
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Discount 3 cannot be applied to an item"));
        checkout("[{\"itemId\":1,\"quantity\":1}]", "[2]", null) // the voucher is item-only
                .andExpect(status().isUnprocessableContent());
        checkout("[{\"itemId\":1,\"quantity\":1,\"discountIds\":[1,1]}]", "[]", null)
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void customAmountsAreSoldWithoutACatalogueItem() throws Exception {
        checkout("[{\"customAmount\":12.50,\"quantity\":2,\"note\":\"Gift wrapping\"}]", "[]", "25.00")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lines[0].itemName").value("Custom Amount"))
                .andExpect(jsonPath("$.lines[0].itemId").doesNotExist())
                .andExpect(jsonPath("$.total").value(25.0));

        checkout("[{\"itemId\":1,\"customAmount\":5,\"quantity\":1}]", "[]", "5.00").andExpect(status().isBadRequest());
        checkout("[{\"quantity\":1}]", "[]", "5.00").andExpect(status().isBadRequest());
    }

    // ---- Reports ----

    @Test
    void reportsShowDiscountsAndOptions() throws Exception {
        checkout("""
                [{"itemId":7,"quantity":2,"discountIds":[1],
                  "modifiers":[{"optionId":1,"quantity":1},{"optionId":3,"quantity":2}]},
                 {"itemId":1,"quantity":1}]""", "[]", "119.60").andExpect(status().isCreated());
        checkout("[{\"itemId\":1,\"quantity\":1}]", "[3]", "14.40").andExpect(status().isCreated());
        String today = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString();

        report("sales-summary", today)
                .andExpect(jsonPath("$.grossSales").value(180.0))
                .andExpect(jsonPath("$.discounts").value(46.0))
                .andExpect(jsonPath("$.netSales").value(134.0));
        report("discounts", today)
                .andExpect(jsonPath("$.discounts[*].name").value(contains("Staff 30%", "Order 10%")))
                .andExpect(jsonPath("$.discounts[0].amount").value(44.4));
        report("modifiers", today)
                .andExpect(jsonPath("$.options[*].optionName").value(contains("Matcha", "Caramel")))
                .andExpect(jsonPath("$.options[0].quantity").value(4))
                .andExpect(jsonPath("$.options[0].amount").value(8.0));
        report("item-sales", today)
                .andExpect(jsonPath("$.items[0].itemName").value("Gift Box"))
                .andExpect(jsonPath("$.items[0].amount").value(148.0));
    }

    // ---- helpers ----

    /**
     * @param amount the Visa payment covering the total, or null to place the order unpaid
     *               (for checks where the total is the thing being asserted)
     */
    private ResultActions checkout(String lines, String orderDiscounts, String amount) throws Exception {
        return checkout(lines, orderDiscounts, amount, null);
    }

    private ResultActions checkout(String lines, String orderDiscounts, String amount, String approvalToken)
            throws Exception {
        String payments = amount == null ? "[]" : "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]";
        String mode = amount == null ? "PLACE_ORDER" : "PAY";
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                {"clientRequestId":"%s","mode":"%s","lines":%s,"discountIds":%s,"payments":%s,"approvalToken":%s}"""
                .formatted(UUID.randomUUID(), mode, lines, orderDiscounts, payments,
                        approvalToken == null ? "null" : "\"" + approvalToken + "\"")));
    }

    private ResultActions report(String name, String day) throws Exception {
        return mockMvc.perform(get("/api/v1/reports/" + name).param("from", day).param("to", day).with(cashier()))
                .andExpect(status().isOk());
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
