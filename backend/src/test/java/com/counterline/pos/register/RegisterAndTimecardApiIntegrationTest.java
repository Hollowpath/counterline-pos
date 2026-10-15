package com.counterline.pos.register;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.timecards.TimecardEvent;
import com.counterline.pos.timecards.TimecardService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Manage Register and Clock In/Out against PostgreSQL, including their concurrency guards. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class RegisterAndTimecardApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    RegisterService registerService;

    @Autowired
    TimecardService timecardService;

    private Long cashierId;

    @BeforeEach
    void resetData() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // also registers and timecards
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
    }

    // ---- Manage Register ----

    @Test
    void opensRecordsPayInOutAndClosesWithTheSessionsSales() throws Exception {
        mockMvc.perform(get("/api/v1/register").with(cashier()))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.lastClosed").value(nullValue()));

        perform(post("/api/v1/register/open"), "").andExpect(status().isCreated())
                .andExpect(jsonPath("$.openedBy").value("Test Cashier"));
        perform(post("/api/v1/register/movements"), "{\"direction\":\"IN\",\"amount\":50,\"reason\":\"Float\"}")
                .andExpect(status().isOk());
        perform(post("/api/v1/register/movements"), "{\"direction\":\"OUT\",\"amount\":12.5,\"reason\":\"Supplies\"}")
                .andExpect(jsonPath("$.payIns").value(50.0))
                .andExpect(jsonPath("$.payOuts").value(12.5))
                .andExpect(jsonPath("$.movements[*].reason").value(contains("Float", "Supplies")));
        perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","lines":[{"itemId":4,"quantity":1}],"payments":[{"paymentMethodId":2,"reference":"ref","amount":29}]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated());

        perform(post("/api/v1/register/close"), "{\"version\":0,\"note\":\" All good \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closedBy").value("Test Cashier"))
                .andExpect(jsonPath("$.closingNote").value("All good"))
                .andExpect(jsonPath("$.sales.orderCount").value(1))
                .andExpect(jsonPath("$.sales.netSales").value(29.0));

        mockMvc.perform(get("/api/v1/register").with(cashier()))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.lastClosed.sales.grossSales").value(29.0));
        perform(post("/api/v1/register/movements"), "{\"direction\":\"IN\",\"amount\":1,\"reason\":\"x\"}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void cannotOpenTwiceOrCloseFromAStaleScreen() throws Exception {
        perform(post("/api/v1/register/open"), "").andExpect(status().isCreated());
        perform(post("/api/v1/register/open"), "").andExpect(status().isConflict());

        perform(post("/api/v1/register/close"), "{\"version\":7}").andExpect(status().isConflict());
        perform(post("/api/v1/register/close"), "{}").andExpect(status().isBadRequest());
    }

    @Test
    void openOrdersMustBePaidOrVoidedBeforeTheRegisterCloses() throws Exception {
        perform(post("/api/v1/register/open"), "").andExpect(status().isCreated());
        perform(post("/api/v1/orders"), """
                {"clientRequestId":"%s","mode":"PLACE_ORDER","lines":[{"itemId":4,"quantity":1}],"payments":[]}"""
                .formatted(UUID.randomUUID())).andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/register").with(cashier()))
                .andExpect(jsonPath("$.openOrders").value(1));
        perform(post("/api/v1/register/close"), "{\"version\":0}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(
                        "1 open order"));

        jdbc.update("UPDATE sale_order SET status = 'VOIDED', void_reason = 'test', voided_by = ?, voided_at = now()", cashierId);
        perform(post("/api/v1/register/close"), "{\"version\":0}").andExpect(status().isOk());
    }

    @Test
    void twoTerminalsOpeningAtOnceLeaveExactlyOneOpenRegister() throws Exception {
        List<Throwable> failures = runTwiceConcurrently(() -> registerService.open(new Terminal(cashierId, 1L)));

        assertThat(failures).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session WHERE closed_at IS NULL", Integer.class))
                .isEqualTo(1);
    }

    // ---- Clock In/Out ----

    @Test
    void thePinShowsWhetherTheyAreClockedInAndTheyChooseInOrOut() throws Exception {
        clockStatus("123456").andExpect(status().isOk())
                .andExpect(jsonPath("$.staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.clockedIn").value(false))
                .andExpect(jsonPath("$.since").doesNotExist());
        // The PIN alone records nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event", Long.class)).isZero();

        clock("123456", "OUT").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Test Cashier is already clocked out"));
        clock("123456", "IN").andExpect(status().isOk())
                .andExpect(jsonPath("$.staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.type").value("IN"));
        clockStatus("123456").andExpect(jsonPath("$.clockedIn").value(true)).andExpect(jsonPath("$.since").exists());
        clock("123456", "IN").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Test Cashier is already clocked in"));
        clock("123456", "OUT").andExpect(jsonPath("$.type").value("OUT"));

        String today = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString();
        mockMvc.perform(get("/api/v1/reports/timecards").param("from", today).param("to", today).with(cashier()))
                .andExpect(jsonPath("$.entries[*].type").value(contains("IN", "OUT")))
                .andExpect(jsonPath("$.entries[0].staffName").value("Test Cashier"));
    }

    @Test
    void aWrongPinIsRejectedWithoutEndingTheTerminalSession() throws Exception {
        clock("000000", "IN").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Invalid PIN"));
        clockStatus("000000").andExpect(status().isUnprocessableContent());
    }

    @Test
    void aDoubleTapClocksInOnce() throws Exception {
        List<Throwable> failures = runTwiceConcurrently(
                () -> timecardService.clock("123456", TimecardEvent.Type.IN, 1L, "10.9.0.1"));

        assertThat(failures).singleElement().extracting(Throwable::getMessage).isEqualTo("Test Cashier is already clocked in");
        assertThat(jdbc.queryForList("SELECT type FROM timecard_event ORDER BY occurred_at, id", String.class))
                .containsExactly("IN");
    }

    // ---- helpers ----

    private ResultActions clock(String pin, String type) throws Exception {
        return perform(post("/api/v1/timecards/clock"), "{\"pin\":\"" + pin + "\",\"type\":\"" + type + "\"}");
    }

    private ResultActions clockStatus(String pin) throws Exception {
        return perform(post("/api/v1/timecards/status"), "{\"pin\":\"" + pin + "\"}");
    }

    private List<Throwable> runTwiceConcurrently(Callable<?> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Object> task = () -> {
            start.await();
            return action.call();
        };
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = List.of(pool.submit(task), pool.submit(task));
            start.countDown();
            List<Throwable> failures = new java.util.ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
            return failures;
        }
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String json) throws Exception {
        MockHttpServletRequestBuilder withAuth = request.with(cashier());
        return mockMvc.perform(json.isEmpty() ? withAuth : withAuth.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
