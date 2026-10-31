package com.counterline.pos.dashboard;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * History on the dashboard keeps closed outlets: their sales stay in a period's totals and charts and their reports stay
 * readable, marked closed, while the live view only has open outlets. An outlet that does not exist is a 404, never zero
 * totals. Also the one money rounding rule for the average order, and the limit on a timecards report.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DashboardHistoryIntegrationTest {

    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;

    private long staff;
    private long closedOutlet;
    private long quietClosedOutlet;
    private long orderNumber;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM outlet WHERE code IN ('OX', 'OY')");
        staff = staffRepository.save(new Staff("History Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        closedOutlet = outlet("OX", "Closed Outlet");
        quietClosedOutlet = outlet("OY", "Quiet Closed Outlet");
        orderNumber = 1000;
    }

    /** The outlets (and the sales and clock events on them) this class made must not outlive it for the other classes. */
    @AfterEach
    void removeWhatThisClassMade() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
        jdbc.update("DELETE FROM outlet WHERE code IN ('OX', 'OY')");
    }

    // ---- closed outlets keep their history ----

    @Test
    void aPeriodsTotalsKeepTheSalesOfAnOutletClosedSinceAndMarkItClosed() throws Exception {
        paid(1, "20.00");
        paid(closedOutlet, "30.00");
        long refunded = paid(closedOutlet, "10.00");
        refund(refunded, "10.00");
        close(closedOutlet);
        close(quietClosedOutlet);

        overview(admin(), "").andExpect(status().isOk())
                // 20 + 30 + 10 paid, 10 refunded: nothing of the closed outlet's trade is missing.
                .andExpect(jsonPath("$.current.orders").value(3))
                .andExpect(jsonPath("$.current.netSales").value(50.0))
                .andExpect(jsonPath("$.current.refunds").value(10.0))
                .andExpect(jsonPath("$.outlets[?(@.name == 'Closed Outlet')].closed").value(contains(true)))
                .andExpect(jsonPath("$.outlets[?(@.name == 'Closed Outlet')].netSales").value(contains(30.0)))
                .andExpect(jsonPath("$.outlets[?(@.name == 'Closed Outlet')].orders").value(contains(2)))
                .andExpect(jsonPath("$.outlets[?(@.outletId == 1)].closed").value(contains(false)))
                // A closed outlet that took nothing in the period is not listed.
                .andExpect(jsonPath("$.outlets[*].name").value(not(hasItem("Quiet Closed Outlet"))));

        // Picked on its own, a closed outlet is shown, with or without sales.
        overview(admin(), "&outletId=" + closedOutlet).andExpect(status().isOk())
                .andExpect(jsonPath("$.current.netSales").value(30.0))
                .andExpect(jsonPath("$.outlets[0].closed").value(true));
        overview(admin(), "&outletId=" + quietClosedOutlet).andExpect(status().isOk())
                .andExpect(jsonPath("$.current.netSales").value(0.0))
                .andExpect(jsonPath("$.outlets[0].name").value("Quiet Closed Outlet"))
                .andExpect(jsonPath("$.outlets[0].closed").value(true));
    }

    @Test
    void aClosedOutletsReportsStayReadableButLiveStatusHasOnlyOpenOutlets() throws Exception {
        paid(closedOutlet, "30.00");
        paid(closedOutlet, "12.00");
        close(closedOutlet);

        report(admin(), closedOutlet, "sales-summary").andExpect(status().isOk())
                .andExpect(jsonPath("$.orderCount").value(2))
                .andExpect(jsonPath("$.netSales").value(42.0));
        mockMvc.perform(get("/api/v1/dashboard/live").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.outlets[*].name").value(not(hasItem("Closed Outlet"))));

        // The outlet list is the open outlets unless history asks for the closed ones too.
        mockMvc.perform(get("/api/v1/dashboard/outlets").with(admin())).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Closed Outlet"))))
                .andExpect(jsonPath("$[0].closed").value(false));
        mockMvc.perform(get("/api/v1/dashboard/outlets").param("includeClosed", "true").with(admin()))
                .andExpect(jsonPath("$[?(@.name == 'Closed Outlet')].closed").value(contains(true)))
                .andExpect(jsonPath("$[?(@.name == 'Outlet 1')].closed").value(contains(false)));
        // A manager still sees only their own outlet, closed ones included or not.
        mockMvc.perform(get("/api/v1/dashboard/outlets").param("includeClosed", "true").with(manager()))
                .andExpect(jsonPath("$[*].name").value(contains("Outlet 1")));
    }

    @Test
    void anOutletThatDoesNotExistIsNotFoundNotZeroTotals() throws Exception {
        long missing = 987_654;

        overview(admin(), "&outletId=" + missing).andExpect(status().isNotFound());
        report(admin(), missing, "sales-summary").andExpect(status().isNotFound());
        report(admin(), missing, "timecards").andExpect(status().isNotFound());
        // A manager asking for another outlet, existing or not, is told the same.
        overview(manager(), "&outletId=" + closedOutlet).andExpect(status().isNotFound());
        overview(manager(), "&outletId=" + missing).andExpect(status().isNotFound());
        report(manager(), closedOutlet, "sales-summary").andExpect(status().isNotFound());
    }

    // ---- rounding ----

    @Test
    void theAverageOrderIsRoundedHalfUpEverywhere() throws Exception {
        // 30.02 over 3 orders is 10.0066...: 10.01 rounded half up, where truncating would say 10.00.
        paid(1, "10.00");
        paid(1, "10.01");
        paid(1, "10.01");

        overview(admin(), "&outletId=1").andExpect(jsonPath("$.current.averageOrder").value(10.01));
        report(admin(), 1, "sales-summary").andExpect(jsonPath("$.diningOptions[0].perPaxAverage").value(10.01));
    }

    // ---- timecards ----

    @Test
    void aTimecardsReportOverTheLimitIsRefusedNotCutShort() throws Exception {
        events(5000);
        report(admin(), 1, "timecards").andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(5000));

        events(1);
        report(admin(), 1, "timecards").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("more than 5000 clock events")))
                .andExpect(jsonPath("$.detail").value(containsString("Choose a shorter range")));
    }

    @Test
    void aShiftLeftOpenAtAnOutletClosedSinceCanStillBeClosedFromTheDashboard() throws Exception {
        jdbc.update("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES (?, 'IN', now() - interval '3 hours', ?)",
                staff, closedOutlet);
        close(closedOutlet);

        report(admin(), closedOutlet, "timecards").andExpect(status().isOk())
                .andExpect(jsonPath("$.openShifts.length()").value(1))
                .andExpect(jsonPath("$.openShifts[0].staffName").value("History Cashier"));

        // Closing an outlet does not stop head office correcting its books: the close needs no open outlet.
        mockMvc.perform(post("/api/v1/dashboard/outlets/" + closedOutlet + "/timecards/" + staff + "/close").with(admin())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"outAt\":\"" + java.time.Instant.now().minus(java.time.Duration.ofHours(1)) + "\","
                                + "\"reason\":\"Outlet closed with the shift open\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffName").value("History Cashier"))
                .andExpect(jsonPath("$.reason").value("Outlet closed with the shift open"));

        report(admin(), closedOutlet, "timecards").andExpect(jsonPath("$.openShifts.length()").value(0))
                .andExpect(jsonPath("$.entries[1].type").value("OUT"))
                .andExpect(jsonPath("$.entries[1].correctedReason").value("Outlet closed with the shift open"));
    }

    // ---- fixtures ----

    private long outlet(String code, String name) {
        return jdbc.queryForObject("INSERT INTO outlet (code, name) VALUES (?, ?) RETURNING id", Long.class, code, name);
    }

    private void close(long outletId) {
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = ?", outletId);
    }

    /** A paid order, closed just now, with its cash payment. */
    private long paid(long outletId, String total) {
        long id = jdbc.queryForObject("""
                INSERT INTO sale_order (order_number, receipt_code, client_request_id, status, total, subtotal, discount_total,
                                        staff_id, outlet_id, created_at, eligibility_at, closed_at)
                VALUES (?, ?, ?, 'CLOSED', ?::numeric, ?::numeric, 0, ?, ?, now() - interval '2 minutes', now() - interval '2 minutes', now())
                RETURNING id""", Long.class, ++orderNumber, "HIST" + orderNumber, UUID.randomUUID(), total, total, staff, outletId);
        jdbc.update("INSERT INTO payment (order_id, payment_method_id, amount, staff_id, created_at) "
                + "VALUES (?, 6, ?::numeric, ?, now())", id, total, staff);
        return id;
    }

    private void refund(long orderId, String ignoredAmount) {
        jdbc.update("UPDATE sale_order SET refunded_at = now(), refund_reason = 'Test', refunded_by = ? WHERE id = ?", staff, orderId);
    }

    /** {@code count} clock events at outlet 1 today, a second apart, from the start of today. */
    private void events(int count) {
        jdbc.update("""
                INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id)
                SELECT ?, CASE WHEN n % 2 = 0 THEN 'IN' ELSE 'OUT' END,
                       (date_trunc('day', now() AT TIME ZONE 'Asia/Kuala_Lumpur') AT TIME ZONE 'Asia/Kuala_Lumpur')
                           + (? + n) * interval '1 second', 1
                FROM generate_series(1, ?) AS n""", staff, existingEvents(), count);
    }

    private int existingEvents() {
        return jdbc.queryForObject("SELECT count(*) FROM timecard_event", Integer.class);
    }

    private ResultActions overview(RequestPostProcessor who, String extra) throws Exception {
        return mockMvc.perform(get("/api/v1/dashboard/overview?from=" + TODAY + "&to=" + TODAY + extra).with(who));
    }

    private ResultActions report(RequestPostProcessor who, long outletId, String name) throws Exception {
        return mockMvc.perform(get("/api/v1/dashboard/outlets/" + outletId + "/reports/" + name)
                .param("from", TODAY).param("to", TODAY).with(who));
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** The manager of outlet 1. */
    private RequestPostProcessor manager() {
        return jwt().jwt(token -> token.subject("1").claim("role", "MANAGER").claim("outlet", 1L).claim("name", "Manager")
                        .claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }
}
