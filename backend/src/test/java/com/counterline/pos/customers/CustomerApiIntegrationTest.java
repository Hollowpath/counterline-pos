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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customers against PostgreSQL: normalisation, uniqueness, search, versioned edits, deactivation, sales, analytics.
 * Cashiers add, search and attach members; a manager or head office edits, deactivates and reactivates them.
 */
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
        performAs(manager(), put("/api/v1/customers/1"), "{\"version\":0,\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"0123450003\",\"dateOfBirth\":\"%s\"}"
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
    void aTrunkZeroAfterTheCountryCodeIsTheSameNumber() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"+60112345678\"}");

        for (String typed : List.of("+60 011-2345 678", "0060 0112345678", "60 0112345678", "0112345678", "+60112345678")) {
            perform(post("/api/v1/customers"), "{\"firstName\":\"Other\",\"lastName\":\"Person\",\"phone\":\"" + typed + "\"}")
                    .andExpect(status().isConflict());
        }
        // However it is typed, it finds and attaches the one member.
        search("PHONE", "+60 0112345678").andExpect(jsonPath("$.items[0].id").value((int) id));
        // A number of another country is left alone.
        perform(post("/api/v1/customers"), "{\"firstName\":\"Sg\",\"lastName\":\"Person\",\"phone\":\"+65 0912 3456\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.phone").value("+6509123456"));
    }

    // ---- Deactivation ----

    @Test
    void aDeactivatedMemberKeepsTheirIdentityAndCannotBeRegisteredAgain() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"01123456782\",\"email\":\"nur@example.com\",\"referenceId\":\"M-7\"}");
        performAs(manager(), post("/api/v1/customers/" + id + "/deactivate"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Registering the same phone, email or reference ID is refused, not made into a new member.
        for (String clash : List.of("\"phone\":\"+60 11-2345 6782\"", "\"phone\":\"0129990001\",\"email\":\"NUR@example.com\"",
                "\"phone\":\"0129990002\",\"referenceId\":\"M-7\"")) {
            perform(post("/api/v1/customers"), "{\"firstName\":\"New\",\"lastName\":\"Owner\"," + clash + "}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.detail").value(containsString("A deactivated member already has this")))
                    .andExpect(jsonPath("$.detail").value(containsString("A manager can reactivate them.")));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isEqualTo(1L);

        // A till no longer finds or opens them (nor their analytics); the dashboard does, flagged inactive.
        mockMvc.perform(get("/api/v1/customers/" + id).with(cashier())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + id + "/analytics").with(cashier())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/" + id + "/analytics").with(dashboard())).andExpect(status().isOk());
        search("PHONE", "0112345").andExpect(jsonPath("$.items").value(empty()));
        mockMvc.perform(get("/api/v1/customers").param("field", "PHONE").param("q", "0112345").with(dashboard()))
                .andExpect(jsonPath("$.items[0].id").value((int) id)).andExpect(jsonPath("$.items[0].active").value(false));
        mockMvc.perform(get("/api/v1/customers/" + id).with(dashboard())).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.phone").value("+601123456782"));
        // An inactive member cannot be edited until reactivated.
        performAs(manager(), put("/api/v1/customers/" + id), "{\"version\":0,\"firstName\":\"Nur\",\"lastName\":\"X\",\"phone\":\"0112345678\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void aManagerOrHeadOfficeReactivatesAMemberWhoIsThenTheSamePersonAgain() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"01123456782\"}");
        performAs(manager(), post("/api/v1/customers/" + id + "/deactivate"), null).andExpect(status().isOk());
        // Idempotent.
        performAs(manager(), post("/api/v1/customers/" + id + "/deactivate"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        performAs(admin(), post("/api/v1/customers/" + id + "/reactivate"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.id").value((int) id))
                .andExpect(jsonPath("$.phone").value("+601123456782"));
        performAs(manager(), post("/api/v1/customers/" + id + "/reactivate"), null).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/" + id).with(cashier())).andExpect(status().isOk());
        checkout(id, 4, 1, "29.00").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isEqualTo(1L);
        performAs(manager(), post("/api/v1/customers/999999/deactivate"), null).andExpect(status().isNotFound());
        performAs(manager(), post("/api/v1/customers/999999/reactivate"), null).andExpect(status().isNotFound());
    }

    // ---- Who may do what ----

    @Test
    void cashiersAddSearchAndAttachButOnlyManagersAndHeadOfficeEditOrChangeTheirState() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        String edit = "{\"version\":0,\"firstName\":\"Nur\",\"lastName\":\"Rahman\",\"phone\":\"0112345678\"}";

        search("NAME", "aisy").andExpect(jsonPath("$.items[0].id").value((int) id));
        mockMvc.perform(get("/api/v1/customers/" + id).with(cashier())).andExpect(status().isOk());
        checkout(id, 4, 1, "29.00").andExpect(status().isCreated());

        perform(put("/api/v1/customers/" + id), edit).andExpect(status().isForbidden());
        perform(post("/api/v1/customers/" + id + "/deactivate"), "{}").andExpect(status().isForbidden());
        perform(post("/api/v1/customers/" + id + "/reactivate"), "{}").andExpect(status().isForbidden());
        // There is no delete at all.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/customers/" + id).with(manager()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isEqualTo(1L);

        performAs(manager(), put("/api/v1/customers/" + id), edit).andExpect(status().isOk());
        performAs(admin(), put("/api/v1/customers/" + id), edit.replace("\"version\":0", "\"version\":1").replace("Rahman", "Lim"))
                .andExpect(status().isOk());
    }

    // ---- A till's search is for finding someone ----

    @Test
    void aTillMustSearchForSomethingAndOnlyTheDashboardMayListEveryone() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        create("{\"firstName\":\"Daniel\",\"lastName\":\"Lim\",\"phone\":\"0127788990\"}");

        for (String[] tooLittle : new String[][]{{"NAME", ""}, {"NAME", "ni"}, {"EMAIL", "a@"}, {"PHONE", ""}, {"PHONE", "011"}, {"PHONE", "+6"},
                {"REFERENCE", ""}}) {
            mockMvc.perform(get("/api/v1/customers").param("field", tooLittle[0]).param("q", tooLittle[1]).with(cashier()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Type at least 3 characters (4 digits for a phone number) to search"));
        }
        mockMvc.perform(get("/api/v1/customers").with(cashier())).andExpect(status().isBadRequest());
        search("NAME", "dan").andExpect(jsonPath("$.items[*].fullName").value(contains("Daniel Lim")));
        search("PHONE", "0112").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));

        mockMvc.perform(get("/api/v1/customers").with(dashboard())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].fullName").value(contains("Daniel Lim", "Nur Aisyah")));
        mockMvc.perform(get("/api/v1/customers").param("field", "NAME").param("q", "ni").with(dashboard())).andExpect(status().isOk());
    }

    @Test
    void aCustomerDisplayTokenIsRefusedOnTheCustomerApi() throws Exception {
        RequestPostProcessor display = jwt().jwt(token -> token.claim("role", "DISPLAY").claim("outlet", 1L).subject("1"))
                .authorities(new SimpleGrantedAuthority("ROLE_DISPLAY"));
        performAs(display, get("/api/v1/customers").param("field", "NAME").param("q", "abc"), null).andExpect(status().isForbidden());
        performAs(display, post("/api/v1/customers"), "{\"firstName\":\"A\",\"lastName\":\"B\",\"phone\":\"0123456789\"}")
                .andExpect(status().isForbidden());
        performAs(display, get("/api/v1/customers/1"), null).andExpect(status().isForbidden());
        // And no session at all is not let in.
        mockMvc.perform(get("/api/v1/customers").param("q", "abc")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).isZero();
    }

    // ---- Two registrations at once ----

    @Test
    void twoRegistrationsOfTheSamePhoneEmailOrReferenceAtOnceMakeOneMemberAndSayWhichClashed() throws Exception {
        record Race(String label, String first, String second) {
        }
        List<Race> races = List.of(
                new Race("phone", "{\"firstName\":\"A\",\"lastName\":\"One\",\"phone\":\"0123450001\",\"email\":\"a1@example.com\"}",
                        "{\"firstName\":\"B\",\"lastName\":\"Two\",\"phone\":\"+60 12-345 0001\",\"email\":\"a2@example.com\"}"),
                new Race("email", "{\"firstName\":\"C\",\"lastName\":\"Three\",\"phone\":\"0123450002\",\"email\":\"same@example.com\"}",
                        "{\"firstName\":\"D\",\"lastName\":\"Four\",\"phone\":\"0123450003\",\"email\":\"SAME@example.com\"}"),
                new Race("reference", "{\"firstName\":\"E\",\"lastName\":\"Five\",\"phone\":\"0123450004\",\"referenceId\":\"SAME-REF\"}",
                        "{\"firstName\":\"F\",\"lastName\":\"Six\",\"phone\":\"0123450005\",\"referenceId\":\"SAME-REF\"}"));
        for (Race race : races) {
            jdbc.execute("TRUNCATE customer RESTART IDENTITY CASCADE");
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<org.springframework.mock.web.MockHttpServletResponse>> requests = new ArrayList<>();
                for (String body : List.of(race.first(), race.second())) {
                    requests.add(pool.submit(() -> {
                        go.await();
                        return perform(post("/api/v1/customers"), body).andReturn().getResponse();
                    }));
                }
                go.countDown();
                List<Integer> statuses = new ArrayList<>();
                String conflict = null;
                for (Future<org.springframework.mock.web.MockHttpServletResponse> request : requests) {
                    var response = request.get(20, TimeUnit.SECONDS);
                    statuses.add(response.getStatus());
                    if (response.getStatus() == 409) {
                        conflict = JsonPath.read(response.getContentAsString(), "$.detail");
                    }
                }
                assertThat(statuses).as(race.label()).containsExactlyInAnyOrder(201, 409);
                // Whether the early check or the unique index caught it, the answer names the field.
                assertThat(conflict).as(race.label()).containsIgnoringCase(race.label().equals("phone") ? "phone" : race.label());
            } finally {
                pool.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customer", Long.class)).as(race.label()).isEqualTo(1L);
        }
    }

    // ---- A placed order and its member ----

    @Test
    void aPlacedOrderKeepsItsMemberWhoIsDeactivatedAndCanStillBePaidAndEdited() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        long orderId = idOf(perform(post("/api/v1/orders"), placeOrderBody(id)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.name").value("Nur Aisyah")));
        performAs(manager(), post("/api/v1/customers/" + id + "/deactivate"), null).andExpect(status().isOk());

        // Still the order's member: shown on it and in its history.
        mockMvc.perform(get("/api/v1/orders/" + orderId).with(cashier())).andExpect(jsonPath("$.customer.id").value((int) id))
                .andExpect(jsonPath("$.customer.name").value("Nur Aisyah"));
        mockMvc.perform(get("/api/v1/orders").param("status", "OPEN").with(cashier()))
                .andExpect(jsonPath("$.items[0].customerName").value("Nur Aisyah"));
        // Edit Order keeps them (and only them); nobody else deactivated is accepted.
        String edit = """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","customerId":%d,"lines":[{"itemId":4,"quantity":2}],"payments":[]}""";
        perform(put("/api/v1/orders/" + orderId), edit.formatted(UUID.randomUUID(), id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.id").value((int) id));
        mockMvc.perform(post("/api/v1/orders/quote").param("editing", String.valueOf(orderId)).with(cashier())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"clientRequestId":"%s","customerId":%d,"lines":[{"itemId":4,"quantity":2}],"payments":[]}"""
                                .formatted(UUID.randomUUID(), id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(58.0));
        // A new sale (not an edit) cannot take them.
        checkout(id, 4, 1, "29.00").andExpect(status().isUnprocessableContent());

        // Paid after the deactivation: the sale is theirs.
        perform(post("/api/v1/orders/" + orderId + "/payments"), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":58.00}]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.customer.id").value((int) id));
        assertThat(jdbc.queryForObject("SELECT customer_id FROM sale_order WHERE id = ?", Long.class, orderId)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loyalty_entry WHERE customer_id = ?", Long.class, id)).isPositive();
        mockMvc.perform(get("/api/v1/customers/" + id + "/analytics").with(dashboard())).andExpect(status().isOk());
    }

    // ---- Search ----

    @Test
    void searchesByEachFieldAndHidesDeactivatedCustomers() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\",\"email\":\"nur@example.com\",\"referenceId\":\"M-1001\"}");
        create("{\"firstName\":\"Daniel\",\"lastName\":\"Lim\",\"phone\":\"0127788990\",\"email\":\"dan@example.com\"}");
        long deactivated = create("{\"firstName\":\"Aisyah\",\"lastName\":\"Old\",\"phone\":\"0129998887\"}");
        performAs(manager(), post("/api/v1/customers/" + deactivated + "/deactivate"), null);

        search("NAME", "aisy").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        search("PHONE", "0112345").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        search("EMAIL", "DAN@").andExpect(jsonPath("$.items[*].fullName").value(contains("Daniel Lim")));
        search("REFERENCE", "M-1001").andExpect(jsonPath("$.items[*].fullName").value(contains("Nur Aisyah")));
        mockMvc.perform(get("/api/v1/customers").with(dashboard()))
                .andExpect(jsonPath("$.items[*].fullName").value(contains("Aisyah Old", "Daniel Lim", "Nur Aisyah")));
    }

    @Test
    void likeWildcardsInTheSearchTermAreTakenLiterally() throws Exception {
        create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        search("NAME", "%%%").andExpect(jsonPath("$.items").value(empty()));
        search("NAME", "___").andExpect(jsonPath("$.items").value(empty()));
    }

    // ---- Edit with optimistic locking ----

    @Test
    void editsWithTheLoadedVersionAndRejectsAStaleOne() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");

        performAs(manager(), put("/api/v1/customers/" + id), "{\"version\":0,\"firstName\":\"Nur\",\"lastName\":\"Rahman\",\"phone\":\"0112345678\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nur Rahman"))
                .andExpect(jsonPath("$.version").value(1));

        // A second terminal still holding version 0 must not overwrite the edit.
        performAs(manager(), put("/api/v1/customers/" + id), "{\"version\":0,\"firstName\":\"Stale\",\"lastName\":\"Edit\",\"phone\":\"0112345678\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflicting change"));
        performAs(manager(), put("/api/v1/customers/" + id), "{\"firstName\":\"No version\"}").andExpect(status().isBadRequest());
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
    void unknownOrDeactivatedCustomersCannotBeAttachedToASale() throws Exception {
        long id = create("{\"firstName\":\"Nur\",\"lastName\":\"Aisyah\",\"phone\":\"0112345678\"}");
        performAs(manager(), post("/api/v1/customers/" + id + "/deactivate"), null);

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

    private ResultActions performAs(RequestPostProcessor who, MockHttpServletRequestBuilder request, String json) throws Exception {
        request.with(who);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }

    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** A manager's dashboard session (it may list everyone, deactivated members included). */
    private RequestPostProcessor dashboard() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()).claim("role", "MANAGER").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
