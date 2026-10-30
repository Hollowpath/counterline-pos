package com.counterline.pos.outlet;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two outlets of the chain: each terminal sees and records only its own outlet's registers, sales,
 * Past Orders, reports and clock-ins. Fixture: catalog-fixture.sql (item 1 Pouch Cheese 16.00,
 * item 4 Tin Caramel 29.00).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class MultiOutletIntegrationTest {

    private static final long FIRST = 1;
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
    private long second;
    private Long secondCashierId;
    private Long secondManagerId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // orders, registers, clock-ins
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO NOTHING");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        // Each staff member works at one outlet.
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, FIRST)).getId();
        managerId = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, FIRST)).getId();
        secondCashierId = staffRepository.save(new Staff("Second Cashier", pinHasher.hash("222222"), StaffRole.STAFF, second)).getId();
        secondManagerId = staffRepository.save(new Staff("Second Manager", pinHasher.hash("888888"), StaffRole.MANAGER, second)).getId();
    }

    @Test
    void eachOutletHasItsOwnRegister() throws Exception {
        open(FIRST).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/register").with(cashier(second))).andExpect(jsonPath("$.open").value(false));

        // The second outlet can't take payment until its own register is open...
        sell(second, 1, "16.00").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));
        // ...and opening it doesn't clash with the first outlet's.
        open(second).andExpect(status().isCreated());
        open(second).andExpect(status().isConflict());
        sell(second, 1, "16.00").andExpect(status().isCreated());
    }

    @Test
    void salesPastOrdersAndReportsBelongToTheirOutlet() throws Exception {
        open(FIRST);
        open(second);
        long first = id(sell(FIRST, 1, "16.00"));
        long other = id(sell(second, 4, "29.00"));
        long placedElsewhere = id(checkout(second, "PLACE_ORDER", 4, null));

        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").with(cashier(FIRST)))
                .andExpect(jsonPath("$.items[*].id").value(contains((int) first)));
        mockMvc.perform(get("/api/v1/orders").param("status", "CLOSED").param("q", "R").with(cashier(FIRST)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/orders/" + other).with(cashier(FIRST))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders/" + other).with(cashier(second))).andExpect(status().isOk());

        // Another outlet's order can't be paid, voided or refunded from here.
        perform(post("/api/v1/orders/" + placedElsewhere + "/payments"), cashier(FIRST), """
                {"clientRequestId":"%s","payments":[{"paymentMethodId":2,"reference":"ref","amount":29.00}]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isNotFound());
        perform(post("/api/v1/orders/" + placedElsewhere + "/void"), manager(FIRST), "{\"reason\":\"Test\"}")
                .andExpect(status().isNotFound());
        perform(post("/api/v1/orders/" + other + "/refund"), manager(FIRST), "{\"reason\":\"Test\"}")
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/reports/sales-summary").param("from", TODAY).param("to", TODAY).with(cashier(FIRST)))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.netSales").value(16.0));
        mockMvc.perform(get("/api/v1/reports/sales-summary").param("from", TODAY).param("to", TODAY).with(cashier(second)))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.netSales").value(29.0))
                .andExpect(jsonPath("$.orderStatuses[*].status").value(contains("Paid", "Open")));

        // The second outlet's open order doesn't stop the first outlet closing its register.
        mockMvc.perform(get("/api/v1/register").with(cashier(FIRST))).andExpect(jsonPath("$.openOrders").value(0));
        mockMvc.perform(get("/api/v1/register").with(cashier(second))).andExpect(jsonPath("$.openOrders").value(1));
        close(FIRST, session(FIRST)).andExpect(status().isOk());
    }

    @Test
    void aDrawerEventAtOneOutletNeverTouchesOrClosesTheOthersRegister() throws Exception {
        openWithFloat(FIRST, 10).andExpect(status().isCreated());
        openWithFloat(second, 20).andExpect(status().isCreated());
        long[] first = session(FIRST);
        long[] other = session(second);

        // The second outlet's cash is its own: it can't pay out the first's float, and its events leave the first's version alone.
        movement(second, "OUT", "20.01").andExpect(status().isConflict());
        movement(second, "OUT", "20").andExpect(status().isOk());
        movement(second, "IN", "3").andExpect(status().isOk());
        cashSale(second).andExpect(status().isCreated());
        assertThat(session(FIRST)).containsExactly(first);

        movement(FIRST, "IN", "5").andExpect(status().isOk());
        cashSale(FIRST).andExpect(status().isCreated());
        long[] firstNow = session(FIRST);
        long[] otherNow = session(second);
        assertThat(firstNow[1]).isGreaterThan(first[1]);
        assertThat(otherNow[1]).isGreaterThan(other[1]);
        mockMvc.perform(get("/api/v1/register").with(cashier(FIRST))).andExpect(jsonPath("$.session.expectedCash").value(31.0));
        mockMvc.perform(get("/api/v1/register").with(cashier(second))).andExpect(jsonPath("$.session.expectedCash").value(19.0));

        // Neither can close the other's session, by its id or by its version.
        close(FIRST, new long[] {otherNow[0], firstNow[1]}).andExpect(status().isConflict());
        close(second, new long[] {firstNow[0], otherNow[1]}).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session WHERE closed_at IS NULL", Integer.class)).isEqualTo(2);

        close(FIRST, firstNow).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/register").with(cashier(second))).andExpect(jsonPath("$.open").value(true));
        close(second, otherNow).andExpect(status().isOk());
    }

    @Test
    void aRequestIdFromAnotherOutletsOrderDoesNotReplayIt() throws Exception {
        open(FIRST);
        open(second);
        String body = """
                {"clientRequestId":"%s","mode":"PAY","lines":[{"itemId":1,"quantity":1}],
                 "payments":[{"paymentMethodId":2,"reference":"ref","amount":16.00}]}""".formatted(UUID.randomUUID());
        perform(post("/api/v1/orders"), cashier(second), body).andExpect(status().isCreated());

        perform(post("/api/v1/orders"), cashier(FIRST), body)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Request id already used"));
        // The outlet's own retry still gets its order back.
        perform(post("/api/v1/orders"), cashier(second), body).andExpect(status().isOk());
    }

    @Test
    void staffClockInOnlyAtTheirOwnOutletAndItIsReportedThere() throws Exception {
        perform(post("/api/v1/timecards/clock"), cashier(second), "{\"pin\":\"222222\",\"type\":\"IN\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.type").value("IN"));
        perform(post("/api/v1/timecards/clock"), cashier(second), "{\"pin\":\"123456\",\"type\":\"IN\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Invalid PIN"));

        mockMvc.perform(get("/api/v1/reports/timecards").param("from", TODAY).param("to", TODAY).with(cashier(FIRST)))
                .andExpect(jsonPath("$.entries").isEmpty());
        mockMvc.perform(get("/api/v1/reports/timecards").param("from", TODAY).param("to", TODAY).with(cashier(second)))
                .andExpect(jsonPath("$.entries[*].staffName").value(contains("Second Cashier")));
    }

    @Test
    void staffLogInOnlyAtTheirOwnOutlet() throws Exception {
        login("222222", second).andExpect(status().isOk()).andExpect(jsonPath("$.staff.fullName").value("Second Cashier"));
        login("222222", FIRST).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid PIN"));
        // Only the outlet's own managers can set up its terminals or approve there.
        perform(post("/api/v1/auth/terminal-setup"), anonymous(), "{\"pin\":\"888888\",\"outletId\":" + FIRST + "}")
                .andExpect(status().isForbidden());
        // The second outlet's own manager can, once its till (set up above) is reset.
        perform(post("/api/v1/auth/terminal-setup"), anonymous(), "{\"pin\":\"888888\",\"outletId\":" + second + "}")
                .andExpect(status().isConflict());
        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Test' WHERE outlet_id = ?", second);
        perform(post("/api/v1/auth/terminal-setup"), anonymous(), "{\"pin\":\"888888\",\"outletId\":" + second + "}")
                .andExpect(status().isOk());
        perform(post("/api/v1/auth/special-access"), cashier(FIRST), "{\"pin\":\"888888\",\"operation\":\"VOID\"}").andExpect(status().isForbidden());
        perform(post("/api/v1/auth/special-access"), cashier(FIRST), "{\"pin\":\"654321\",\"operation\":\"VOID\"}").andExpect(status().isOk());
    }

    /** Logs in on an iPad set up at that outlet by its manager (replacing the outlet's till, if any). */
    private ResultActions login(String pin, long outletId) throws Exception {
        String managerPin = outletId == FIRST ? "654321" : "888888";
        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Test' WHERE outlet_id = ? AND reset_at IS NULL",
                outletId);
        String setUp = perform(post("/api/v1/auth/terminal-setup"), anonymous(),
                "{\"pin\":\"" + managerPin + "\",\"outletId\":" + outletId + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String deviceKey = JsonPath.read(setUp, "$.deviceKey");
        return perform(post("/api/v1/auth/login"), anonymous(), "{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + deviceKey + "\"}");
    }

    /** A different client address per call, so the shared login throttle doesn't carry over. */
    private static RequestPostProcessor anonymous() {
        return request -> {
            request.setRemoteAddr("10.12.0." + (int) (Math.random() * 200 + 1));
            return request;
        };
    }

    private ResultActions open(long outletId) throws Exception {
        return mockMvc.perform(post("/api/v1/register/open").with(cashier(outletId)));
    }

    private ResultActions openWithFloat(long outletId, int openingFloat) throws Exception {
        return perform(post("/api/v1/register/open"), cashier(outletId), "{\"openingFloat\":" + openingFloat + "}");
    }

    /** The outlet's open session as a screen would show it: its id and version. */
    private long[] session(long outletId) {
        return jdbc.queryForObject("SELECT id, version FROM register_session WHERE outlet_id = ? AND closed_at IS NULL",
                (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, outletId);
    }

    private ResultActions close(long outletId, long[] seen) throws Exception {
        return perform(post("/api/v1/register/close"), cashier(outletId),
                "{\"sessionId\":" + seen[0] + ",\"version\":" + seen[1] + "}");
    }

    private ResultActions movement(long outletId, String direction, String amount) throws Exception {
        return perform(post("/api/v1/register/movements"), cashier(outletId),
                "{\"direction\":\"" + direction + "\",\"amount\":" + amount + ",\"reason\":\"Test\"}");
    }

    /** A RM16.00 cash sale. */
    private ResultActions cashSale(long outletId) throws Exception {
        Long cash = jdbc.queryForObject("SELECT id FROM payment_method WHERE kind = 'CASH' ORDER BY id LIMIT 1", Long.class);
        return perform(post("/api/v1/orders"), cashier(outletId), """
                {"clientRequestId":"%s","lines":[{"itemId":1,"quantity":1}],
                 "payments":[{"paymentMethodId":%d,"reference":"ref","amount":16.00}]}"""
                .formatted(UUID.randomUUID(), cash));
    }

    private ResultActions sell(long outletId, long itemId, String amount) throws Exception {
        return checkout(outletId, "PAY", itemId, amount);
    }

    private ResultActions checkout(long outletId, String mode, long itemId, String amount) throws Exception {
        String payments = amount == null ? "[]" : "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":" + amount + "}]";
        return perform(post("/api/v1/orders"), cashier(outletId), """
                {"clientRequestId":"%s","mode":"%s","lines":[{"itemId":%d,"quantity":1}],"payments":%s}"""
                .formatted(UUID.randomUUID(), mode, itemId, payments));
    }

    private ResultActions perform(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                                  RequestPostProcessor auth, String json) throws Exception {
        return mockMvc.perform(request.with(auth).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    /** A session of that outlet's cashier. */
    private RequestPostProcessor cashier(long outletId) {
        Long staffId = outletId == FIRST ? cashierId : secondCashierId;
        return jwt().jwt(token -> token.claim("outlet", outletId).subject(staffId.toString()));
    }

    private RequestPostProcessor manager(long outletId) {
        Long staffId = outletId == FIRST ? managerId : secondManagerId;
        return jwt().jwt(token -> token.claim("outlet", outletId).subject(staffId.toString()).claim("role", "MANAGER"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
