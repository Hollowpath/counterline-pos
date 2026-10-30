package com.counterline.pos.display;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A customer display's life: an access code that lasts ten minutes, at most three waiting at an outlet,
 * one connection per code, every display endpoint refusing a disconnected display or a closed outlet,
 * and the dashboard listing and disconnecting them (head office any outlet's, a manager their own).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DisplayLifecycleIntegrationTest {

    private static final String WRONG = "Wrong access code. Check the code on the POS under Customer Display.";
    private static final String DASHBOARD = "/api/v1/dashboard/displays";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    DisplayService displays;

    private long second;
    private Long cashierId;

    /** Outlets are shared with the other test classes: leave them all open. */
    @AfterEach
    void reopenOutlets() {
        jdbc.update("UPDATE outlet SET active = TRUE");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, customer_display, staff, customer RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO UPDATE SET active = TRUE");
        jdbc.update("UPDATE outlet SET active = TRUE");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
    }

    // ---- The access code ----

    @Test
    void aCodeLastsTenMinutesThenTheDisplayIsGone() throws Exception {
        long justInTime = create(1);
        age(justInTime, 9 * 60 + 55);
        connect(code(justInTime)).andExpect(status().isOk());

        long tooLate = create(1);
        age(tooLate, 10 * 60 + 1);
        connect(code(tooLate)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(WRONG));
        pos(get("/api/v1/displays/" + tooLate), 1, null).andExpect(status().isNotFound());

        // Exactly ten minutes is over too: the code is good for less than that.
        long onTheMinute = create(1);
        age(onTheMinute, 10 * 60);
        connect(code(onTheMinute)).andExpect(status().isUnprocessableContent());
    }

    @Test
    void aWrongAnExpiredAUsedAndAClosedOutletsCodeAreAnsweredAlike() throws Exception {
        long expired = create(1);
        String expiredCode = code(expired);
        age(expired, 11 * 60);
        long used = create(1); // creating one cleans the ran-out row away, whose number can then be reused
        String usedCode = code(used);
        connect(usedCode).andExpect(status().isOk());
        long atClosedOutlet = create(second);
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = ?", second);

        String wrong = connect(unusedNumber()).andExpect(status().isUnprocessableContent()).andReturn().getResponse().getContentAsString();
        for (String code : List.of(expiredCode, usedCode, code(atClosedOutlet))) {
            assertThat(connect(code).andExpect(status().isUnprocessableContent()).andReturn().getResponse().getContentAsString())
                    .isEqualTo(wrong);
        }
        assertThat(JsonPath.<String>read(wrong, "$.detail")).isEqualTo(WRONG);
    }

    @Test
    void anOutletHasThreeWaitingCodesAtMostAndExpiredOnesAreCleanedAway() throws Exception {
        for (int i = 0; i < 3; i++) {
            pos(post("/api/v1/displays"), 1, null).andExpect(status().isCreated());
        }
        pos(post("/api/v1/displays"), 1, null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Too many displays are waiting to connect. Connect or disconnect one first."));
        // Another outlet has its own three.
        pos(post("/api/v1/displays"), second, null).andExpect(status().isCreated());

        // A connected display no longer waits: a place is free again.
        long connecting = jdbc.queryForObject("SELECT min(id) FROM customer_display WHERE outlet_id = 1", Long.class);
        connect(code(connecting)).andExpect(status().isOk());
        pos(post("/api/v1/displays"), 1, null).andExpect(status().isCreated());
        pos(post("/api/v1/displays"), 1, null).andExpect(status().isConflict());

        // Codes that ran out are gone, not counted, and their rows removed.
        jdbc.update("UPDATE customer_display SET created_at = now() - interval '11 minutes' WHERE connected_at IS NULL");
        pos(post("/api/v1/displays"), 1, null).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_display WHERE connected_at IS NULL", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_display WHERE connected_at IS NOT NULL", Long.class)).isEqualTo(1L);
    }

    @Test
    void displaysCreatedAtOnceNeverPassTheCap() throws Exception {
        int attempts = 8;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        int created = 0;
        try {
            List<Future<?>> creations = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                creations.add(pool.submit(() -> {
                    go.await();
                    return displays.create(new Terminal(cashierId, 1L));
                }));
            }
            go.countDown();
            for (Future<?> creation : creations) {
                try {
                    creation.get(20, TimeUnit.SECONDS);
                    created++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ConflictException.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(created).isEqualTo(DisplayService.MAX_WAITING_PER_OUTLET);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_display WHERE outlet_id = 1", Long.class)).isEqualTo(3L);
    }

    @Test
    void ofManyDisplaysEnteringOneCodeAtOnceExactlyOneConnects() throws Exception {
        String code = code(create(1));
        int attempts = 6;
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        int connected = 0;
        try {
            List<Future<?>> connections = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String client = "10.50.0." + (i + 1);
                connections.add(pool.submit(() -> {
                    go.await();
                    return displays.connect(code, client);
                }));
            }
            go.countDown();
            for (Future<?> connection : connections) {
                try {
                    connection.get(20, TimeUnit.SECONDS);
                    connected++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(BusinessRuleException.class).hasMessage(WRONG);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(connected).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer_display WHERE connected_at IS NOT NULL", Long.class)).isEqualTo(1L);
    }

    // ---- Every display endpoint ----

    @Test
    void everyDisplayEndpointRefusesADisconnectedDisplayAndAClosedOutletTheSameWay() throws Exception {
        long id = create(1);
        String token = connectedToken(code(id));
        List<MockHttpServletRequestBuilder> endpoints = List.of(
                get("/api/v1/display/state"), get("/api/v1/display/posters"),
                post("/api/v1/display/member-login").contentType(MediaType.APPLICATION_JSON).content("{\"phone\":\"0112345678\"}"),
                delete("/api/v1/display/member"));

        display(get("/api/v1/display/state"), token).andExpect(status().isOk());
        display(get("/api/v1/display/posters"), token).andExpect(status().isOk());

        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = 1");
        for (MockHttpServletRequestBuilder endpoint : endpoints) {
            display(endpoint, token).andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("Display unavailable"));
        }
        // It comes back with the outlet.
        jdbc.update("UPDATE outlet SET active = TRUE WHERE id = 1");
        display(get("/api/v1/display/state"), token).andExpect(status().isOk());

        pos(delete("/api/v1/displays/" + id), 1, null).andExpect(status().isNoContent());
        for (MockHttpServletRequestBuilder endpoint : endpoints) {
            display(endpoint, token).andExpect(status().isUnauthorized());
        }
        // Disconnected and closed: still the disconnection that is said.
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = 1");
        display(get("/api/v1/display/state"), token).andExpect(status().isUnauthorized());
    }

    // ---- The dashboard ----

    @Test
    void headOfficeSeesAllOutletsDisplaysAndAManagerTheirOwn() throws Exception {
        long connected = create(1);
        connect(code(connected)).andExpect(status().isOk());
        long waiting = create(1);
        long otherWaiting = create(second);
        long expired = create(second);
        age(expired, 11 * 60);

        admin(get(DASHBOARD)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains((int) connected, (int) waiting, (int) otherWaiting)))
                .andExpect(jsonPath("$[*].outletName").value(contains("Outlet 1", "Outlet 1", "Outlet 2")))
                .andExpect(jsonPath("$[*].connected").value(contains(true, false, false)))
                .andExpect(jsonPath("$[0].lastSeenAt").exists())
                .andExpect(jsonPath("$[1].accessCode").doesNotExist());
        manager(get(DASHBOARD), 1).andExpect(jsonPath("$[*].id").value(contains((int) connected, (int) waiting)));
        manager(get(DASHBOARD), second).andExpect(jsonPath("$[*].id").value(contains((int) otherWaiting)));
        pos(get(DASHBOARD), 1, null).andExpect(status().isForbidden());
    }

    @Test
    void aLostDisplayIsDisconnectedFromTheDashboardAndItsTokenStopsAtOnce() throws Exception {
        long lost = create(1);
        String token = connectedToken(code(lost));
        display(get("/api/v1/display/state"), token).andExpect(status().isOk());

        // Another outlet's manager cannot see it, let alone disconnect it.
        manager(post(DASHBOARD + "/" + lost + "/disconnect"), second).andExpect(status().isNotFound());
        display(get("/api/v1/display/state"), token).andExpect(status().isOk());
        // Nor can a cashier.
        pos(post(DASHBOARD + "/" + lost + "/disconnect"), 1, null).andExpect(status().isForbidden());

        manager(post(DASHBOARD + "/" + lost + "/disconnect"), 1).andExpect(status().isNoContent());
        display(get("/api/v1/display/state"), token).andExpect(status().isUnauthorized());
        admin(get(DASHBOARD)).andExpect(jsonPath("$").isEmpty());
        // Once only.
        admin(post(DASHBOARD + "/" + lost + "/disconnect")).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM customer_display WHERE id = ?", Boolean.class, lost)).isTrue();
    }

    @Test
    void headOfficeDisconnectsAWaitingDisplayAndAnyOutletsEvenAClosedOne() throws Exception {
        long waiting = create(1);
        String waitingCode = code(waiting);
        admin(post(DASHBOARD + "/" + waiting + "/disconnect")).andExpect(status().isNoContent());
        connect(waitingCode).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.detail").value(WRONG));

        long atSecond = create(second);
        String token = connectedToken(code(atSecond));
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = ?", second);
        admin(get(DASHBOARD)).andExpect(jsonPath("$[0].outletName").value("Outlet 2"));
        admin(post(DASHBOARD + "/" + atSecond + "/disconnect")).andExpect(status().isNoContent());
        display(get("/api/v1/display/state"), token).andExpect(status().isUnauthorized());

        // A code that has run out is not a display to disconnect.
        long ranOut = create(1);
        age(ranOut, 11 * 60);
        admin(post(DASHBOARD + "/" + ranOut + "/disconnect")).andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private long create(long outlet) throws Exception {
        return ((Number) JsonPath.read(pos(post("/api/v1/displays"), outlet, null).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String code(long displayId) {
        return jdbc.queryForObject("SELECT access_code FROM customer_display WHERE id = ?", String.class, displayId);
    }

    /** A six-digit number no display is waiting for. */
    private String unusedNumber() {
        for (int number = 0; number < 1_000_000; number++) {
            String candidate = "%06d".formatted(number);
            if (jdbc.queryForObject("SELECT count(*) FROM customer_display WHERE access_code = ?", Long.class, candidate) == 0) {
                return candidate;
            }
        }
        throw new IllegalStateException();
    }

    private void age(long displayId, int seconds) {
        jdbc.update("UPDATE customer_display SET created_at = now() - (? * interval '1 second') WHERE id = ?", seconds, displayId);
    }

    private ResultActions connect(String code) throws Exception {
        return mockMvc.perform(post("/api/v1/display/connect")
                .with(request -> {
                    request.setRemoteAddr("10.20.0." + (int) (Math.random() * 200 + 1));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content("{\"accessCode\":\"" + code + "\"}"));
    }

    private String connectedToken(String code) throws Exception {
        return JsonPath.read(connect(code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions display(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions pos(MockHttpServletRequestBuilder request, long outlet, String json) throws Exception {
        request.with(jwt().jwt(token -> token.claim("outlet", outlet).subject(cashierId.toString()).claim("name", "Test Cashier")));
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
        RequestPostProcessor admin = jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return mockMvc.perform(request.with(admin));
    }

    private ResultActions manager(MockHttpServletRequestBuilder request, long outlet) throws Exception {
        RequestPostProcessor manager = jwt().jwt(token -> token.claim("outlet", outlet).subject(cashierId.toString())
                        .claim("role", "MANAGER").claim("name", "A Manager"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
        return mockMvc.perform(request.with(manager));
    }
}
