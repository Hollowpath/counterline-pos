package com.counterline.pos.customers;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Customers against PostgreSQL: normalisation, uniqueness, trigram search, versioned edits, sales, analytics. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class CustomerApiIntegrationTest {

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
    void resetData() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // cascades to orders
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        TestRegister.open(jdbc, cashierId);
    }

    // ---- Create, validate, normalise ----

    @Test
    void createsACustomerWithNormalisedContactDetails() throws Exception {
        perform(post("/api/v1/customers"), """
                {"firstName":"  Nur ","lastName":"Aisyah","email":"Nur.Aisyah@Example.com","phone":"011-2345 6782",
                 "dateOfBirth":"1999-07-11","gender":"FEMALE","marketingEmail":true,"city":"Petaling Jaya","notes":"  "}""")
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/customers/")))
                .andExpect(jsonPath("$.fullName").value("Nur Aisyah"))
                .andExpect(jsonPath("$.phone").value("+601123456782"))
                .andExpect(jsonPath("$.email").value("nur.aisyah@example.com"))
                .andExpect(jsonPath("$.marketingEmail").value(true))
                .andExpect(jsonPath("$.marketingSms").value(false))
                .andExpect(jsonPath("$.notes").value(nullValue()))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void aBirthDateUpToTodayIsAcceptedAndTomorrowsIsNot() throws Exception {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur"));
        String body = "{\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"%s\",\"dateOfBirth\":\"%s\"}";
        perform(post("/api/v1/customers"), body.formatted("0123450001", today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Date of birth can't be in the future"));
        perform(post("/api/v1/customers"), body.formatted("0123450002", today.plusYears(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Date of birth can't be in the future"));
        perform(post("/api/v1/customers"), body.formatted("0123450003", today))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dateOfBirth").value(today.toString()));
        perform(post("/api/v1/customers"), body.formatted("0123450004", today.minusDays(1))).andExpect(status().isCreated());
        // Editing is held to the same rule.
        perform(put("/api/v1/customers/1"), "{\"version\":0,\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"0123450003\",\"dateOfBirth\":\"%s\"}"
                .formatted(today.plusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value("Date of birth can't be in the future"));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        // First name, last name and phone number are required.
        perform(post("/api/v1/customers"), "{\"lastName\":\"Only\",\"phone\":\"0123456789\"}").andExpect(status().isBadRequest());
        perform(post("/api/v1/customers"), "{\"firstName\":\"A\",\"phone\":\"0123456789\"}").andExpect(status().isBadRequest());
        perform(post("/api/v1/customers"), "{\"firstName\":\"A\",\"lastName\":\"B\"}").andExpect(status().isBadRequest());
        perform(post("/api/v1/customers"), "{\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"0123456789\",\"email\":\"not-an-email\"}")
                .andExpect(status().isBadRequest());
        perform(post("/api/v1/customers"), "{\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"0123456789\",\"dateOfBirth\":\"2999-01-01\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Date of birth can't be in the future"));
        perform(post("/api/v1/customers"), "{\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"12345\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Enter a valid phone number"));
    }

    @Test
    void phoneNumbersAreUniqueHoweverTheyAreTyped() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"01123456782\"}");

        perform(post("/api/v1/customers"), "{\"firstName\":\"Other\",\"lastName\":\"Person\",\"phone\":\"+60 11-2345 6782\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Customer Nur Aisyah already has this phone number"));
    }

    @Test
    void aDeletedCustomersPhoneCanBeRegisteredAgain() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"01123456782\"}");
        mockMvc.perform(delete("/api/v1/customers/" + id).with(cashier())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/customers/" + id).with(cashier())).andExpect(status().isNotFound());
        perform(post("/api/v1/customers"), "{\"firstName\":\"New\",\"lastName\":\"Owner\",\"phone\":\"01123456782\"}")
                .andExpect(status().isCreated());
    }

    // ---- Search ----

    @Test
    void searchesByEachFieldAndHidesDeletedCustomers() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\",\"email\":\"nur@example.com\",\"referenceId\":\"M-1001\"}");
        create("{\"firstName\":\"Daniel\",\"lastName\":\"Lim\",\"phone\":\"0127788990\",\"email\":\"dan@example.com\"}");
        long deleted = create("{\"firstName\":\"Aisyah\",\"lastName\":\"Old\",\"phone\":\"0129998887\"}");
        mockMvc.perform(delete("/api/v1/customers/" + deleted).with(cashier()));

        search("NAME", "aisy").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        search("PHONE", "0112345").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        search("EMAIL", "DAN@").andExpect(jsonPath("$.items[*].fullName").value(contains("Daniel Lim")));
        search("REFERENCE", "M-1001").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        search("NAME", "").andExpect(jsonPath("$.items[*].fullName").value(contains("Daniel Lim", "Nur Aisyah")));
    }

    @Test
    void likeWildcardsInTheSearchTermAreTakenLiterally() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        search("NAME", "%").andExpect(jsonPath("$.items").value(empty()));
        search("NAME", "_").andExpect(jsonPath("$.items").value(empty()));
    }

    // ---- Edit with optimistic locking ----

    @Test
    void editsWithTheLoadedVersionAndRejectsAStaleOne() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        perform(put("/api/v1/customers/" + id), "{\"version\":0,\"firstName\":\"Nur\",\"lastName\":\"Rahman\",\"phone\":\"0112345678\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nur Rahman"))
                .andExpect(jsonPath("$.version").value(1));

        // A second terminal still holding version 0 must not overwrite the edit.
        perform(put("/api/v1/customers/" + id), "{\"version\":0,\"firstName\":\"Stale\",\"lastName\":\"Edit\",\"phone\":\"0112345678\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicting change"));
        perform(put("/api/v1/customers/" + id), "{\"firstName\":\"No version\"}").andExpect(status().isBadRequest());
    }

    // ---- Sales and analytics ----

    @Test
    void aSaleCarriesItsCustomerIntoOrderHistory() throws Exception {
        long customerId = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        long orderId = idOf(checkout(customerId, 4, 1, "29.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.name").value("Nur Aisyah"))
                .andExpect(jsonPath("$.customer.phone").value("+60112345678")));

        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").with(cashier()))
                .andExpect(jsonPath("$.items[0].id").value((int) orderId))
                .andExpect(jsonPath("$.items[0].customerName").value("Nur Aisyah"));
    }

    @Test
    void unknownOrDeletedCustomersCannotBeAttachedToASale() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        mockMvc.perform(delete("/api/v1/customers/" + id).with(cashier()));

        checkout(id, 4, 1, "29.00").andExpect(status().isUnprocessableContent());
        checkout(999_999, 4, 1, "29.00").andExpect(status().isUnprocessableContent());
    }

    @Test
    void analyticsCountOnlyPaidNonRefundedOrders() throws Exception {
        long customerId = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        checkout(customerId, 1, 3, "48.00").andExpect(status().isCreated()); // 3 × Pouch Cheese 16.00
        checkout(customerId, 4, 1, "29.00").andExpect(status().isCreated()); // 1 × Tin Caramel 29.00
        long refunded = idOf(checkout(customerId, 4, 5, "145.00"));
        jdbc.update("UPDATE sale_order SET refund_reason = 'x', refunded_by = ?, refunded_at = now() WHERE id = ?",
                cashierId, refunded);
        perform(post("/api/v1/orders"), placeOrderBody(customerId)).andExpect(status().isCreated()); // unpaid

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/analytics").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.orderTotal").value(77.0))
                .andExpect(jsonPath("$.orderAverage").value(38.5))
                .andExpect(jsonPath("$.lastOrderAt").exists())
                .andExpect(jsonPath("$.mostOrderedItems[*].itemName").value(contains("Pouch Cheese", "Tin Caramel")))
                .andExpect(jsonPath("$.mostOrderedItems[0].quantity").value(3));
    }

    @Test
    void aNewCustomerHasEmptyAnalytics() throws Exception {
        long customerId = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        mockMvc.perform(get("/api/v1/customers/" + customerId + "/analytics").with(cashier()))
                .andExpect(jsonPath("$.orderCount").value(0))
                .andExpect(jsonPath("$.orderAverage").value(0.0))
                .andExpect(jsonPath("$.lastOrderAt").value(nullValue()))
                .andExpect(jsonPath("$.mostOrderedItems").value(empty()));
    }

    // ---- helpers ----

    private long create(String json) throws Exception {
        return idOf(perform(post("/api/v1/customers"), json).andExpect(status().isCreated()));
    }

    private ResultActions search(String field, String q) throws Exception {
        return mockMvc.perform(get("/api/v1/customers").param("field", field).param("q", q).with(cashier()))
                .andExpect(status().isOk());
    }

    private ResultActions checkout(long customerId, long itemId, int quantity, String amount) throws Exception {
        return perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","customerId":%d,"lines":[{"itemId":%d,"quantity":%d}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":%s}]}"""
                .formatted(UUID.randomUUID(), customerId, itemId, quantity, amount));
    }

    private static String placeOrderBody(long customerId) {
        return """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","customerId":%d,
                 "lines":[{"itemId":4,"quantity":9}],"payments":[]}""".formatted(UUID.randomUUID(), customerId);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String json) throws Exception {
        return mockMvc.perform(request.with(cashier()).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
