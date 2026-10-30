package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The till iPad's lifecycle: set-up, reset, and what a reset does to the sessions it issued. A till or
 * approval token names its device, so resetting the device ends them at once; a dashboard token has none.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class DeviceLifecycleIntegrationTest {

    private static final String DEVICES = "/api/v1/dashboard/devices";
    private static final String NO_TOKEN = null;

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

    @Autowired
    JwtEncoder jwtEncoder;

    @Autowired
    AuthService authService;

    @Autowired
    JwtDecoder jwtDecoder;

    @Autowired
    ManagerApproval managerApproval;

    @Autowired
    TillDeviceService devices;

    private long second;
    private Staff cashier;
    private Staff manager;
    private Staff secondManager;

    /** Outlets are shared with the other test classes: leave them all open. */
    @AfterEach
    void reopenOutlets() {
        jdbc.update("UPDATE outlet SET active = TRUE");
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // also devices
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO UPDATE SET active = TRUE");
        jdbc.update("UPDATE outlet SET active = TRUE");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        cashier = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L));
        manager = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L));
        secondManager = staffRepository.save(new Staff("Second Manager", pinHasher.hash("888888"), StaffRole.MANAGER, second));
        jdbc.update("UPDATE staff SET email = 'manager@counterline.test', password_hash = 'x' WHERE id = ?", manager.getId());
    }

    // ---- A reset ends the sessions of its device ----

    @Test
    void resettingAnIPadEndsEveryTillAndApprovalSessionItIssuedAndNothingElse() throws Exception {
        String key = setUpIPad("654321", 1L);
        long deviceId = activeDevice(1L);
        String tillToken = login(key, "123456");
        String managerTillToken = login(key, "654321");
        String approval = JsonPath.read(call(post("/api/v1/auth/special-access"), tillToken,
                "{\"pin\":\"654321\",\"operation\":\"DISCOUNT\"}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
        String dashboardToken = tokenService.issueDashboard(staffRepository.findById(manager.getId()).orElseThrow()).value();
        String headOffice = tokenService.issueAdmin(new AdminUser("admin@counterline.test", "Head Office", "x", Instant.now())).value();

        call(get("/api/v1/auth/me"), tillToken, null).andExpect(status().isOk());
        assertThat(callStatus(get("/api/v1/auth/me"), approval)).isNotEqualTo(401);
        call(get("/api/v1/dashboard/outlets"), dashboardToken, null).andExpect(status().isOk());

        call(post(DEVICES + "/" + deviceId + "/reset"), headOffice, null).andExpect(status().isNoContent());

        // Gone at once, all with the same plain 401.
        String refused = call(get("/api/v1/auth/me"), tillToken, null).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(call(get("/api/v1/auth/me"), managerTillToken, null).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString()).isEqualTo(refused);
        assertThat(call(get("/api/v1/auth/me"), approval, null).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString()).isEqualTo(refused);
        // A dashboard session is not a till's, and head office has no device at all.
        call(get("/api/v1/dashboard/outlets"), dashboardToken, null).andExpect(status().isOk());
        call(get(DEVICES), headOffice, null).andExpect(status().isOk());

        // The replacement iPad has its own sessions; the old ones stay dead.
        String replacementKey = setUpIPad("654321", 1L);
        call(get("/api/v1/auth/me"), login(replacementKey, "123456"), null).andExpect(status().isOk());
        call(get("/api/v1/auth/me"), tillToken, null).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagersApprovalIsOnTheDeviceItWasAskedOnAndDiesWithIt() throws Exception {
        String key = setUpIPad("654321", 1L);
        long deviceId = activeDevice(1L);
        String tillToken = login(key, "123456");
        Staff cashierNow = staffRepository.findById(cashier.getId()).orElseThrow();

        String live = approvalFor(tillToken);
        String lost = approvalFor(tillToken);
        // It names the requesting session's device, and an approval is good while that device is set up.
        assertThat(((Number) jwtDecoder.decode(live).getClaim("device")).longValue()).isEqualTo(deviceId);
        assertThat(managerApproval.requireManager(cashierNow, live).getId()).isEqualTo(manager.getId());

        callAs(post(DEVICES + "/" + deviceId + "/reset"), admin(), null).andExpect(status().isNoContent());
        org.junit.jupiter.api.Assertions.assertThrows(SpecialAccessDeniedException.class,
                () -> managerApproval.requireManager(cashierNow, lost));
    }

    @Test
    void aTillTokenNeedsAnActiveDeviceAtItsOwnOutlet() throws Exception {
        setUpIPad("654321", 1L);
        setUpIPad("888888", second);
        long first = activeDevice(1L);
        long other = activeDevice(second);
        Staff staff = staffRepository.findById(cashier.getId()).orElseThrow();

        String good = tokenService.issue(staff, 1L, first).value();
        call(get("/api/v1/auth/me"), good, null).andExpect(status().isOk());
        String refused = call(get("/api/v1/auth/me"), tokenService.issue(staff, 1L, other).value(), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        // Another outlet's device, a device that never existed, and no device at all: the same refusal.
        assertThat(call(get("/api/v1/auth/me"), tokenService.issue(staff, 1L, 999_999L).value(), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString()).isEqualTo(refused);
        assertThat(call(get("/api/v1/auth/me"), withoutDevice(staff), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString()).isEqualTo(refused);
        // A device reset straight in the database counts the same.
        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Test' WHERE id = ?", first);
        call(get("/api/v1/auth/me"), good, null).andExpect(status().isUnauthorized());
    }

    @Test
    void anIPadCanBeResetOnceAndTheSessionDoingItIsEndedByIt() throws Exception {
        String key = setUpIPad("654321", 1L);
        long deviceId = activeDevice(1L);
        String managersOwnSession = login(key, "654321");

        // The manager resets the very iPad they are working on.
        call(post(DEVICES + "/" + deviceId + "/reset"), managersOwnSession, null).andExpect(status().isNoContent());
        call(get("/api/v1/auth/me"), managersOwnSession, null).andExpect(status().isUnauthorized());
        // Once only: it is not an iPad in use any more.
        callAs(post(DEVICES + "/" + deviceId + "/reset"), admin(), null).andExpect(status().isNotFound());
        callAs(get(DEVICES), admin(), null).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"123456\",\"deviceKey\":\"" + key + "\"}"))
                .andExpect(status().isGone());
    }

    @Test
    void anIPadOfAnotherOutletCannotBeResetByAManager() throws Exception {
        setUpIPad("888888", second);
        long deviceId = activeDevice(second);
        String firstOutletsManager = login(setUpIPad("654321", 1L), "654321");

        call(post(DEVICES + "/" + deviceId + "/reset"), firstOutletsManager, null).andExpect(status().isNotFound());
        call(get(DEVICES), firstOutletsManager, null).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outletName").value("Outlet 1"));
        assertThat(jdbc.queryForObject("SELECT reset_at IS NULL FROM till_device WHERE id = ?", Boolean.class, deviceId)).isTrue();
    }

    // ---- The outlet's closing and reopening ----

    @Test
    void anOutletClosedAndReopenedKeepsItsIPadButNotTheSessionsOfBefore() throws Exception {
        String key = setUpIPad("888888", second);
        String before = login(key, "888888");
        call(get("/api/v1/auth/me"), before, null).andExpect(status().isOk());

        callAs(post("/api/v1/dashboard/admin/outlets/" + second + "/deactivate"), admin(), null).andExpect(status().isOk());
        // Closed: nobody logs in on it, and the sessions of before are over.
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pin\":\"888888\",\"deviceKey\":\"" + key + "\"}"))
                .andExpect(status().isNotFound());
        call(get("/api/v1/auth/me"), before, null).andExpect(status().isUnauthorized());
        // Head office still sees the iPad, and can reset it.
        callAs(get(DEVICES), admin(), null).andExpect(jsonPath("$[0].outletName").value("Outlet 2"));

        callAs(post("/api/v1/dashboard/admin/outlets/" + second + "/reactivate"), admin(), null).andExpect(status().isOk());
        String after = login(key, "888888");
        call(get("/api/v1/auth/me"), after, null).andExpect(status().isOk());
        // The old session does not come back with the outlet.
        call(get("/api/v1/auth/me"), before, null).andExpect(status().isUnauthorized());
    }

    // ---- Races ----

    @Test
    void twoIPadsSetUpAtOnceLeaveTheOutletWithExactlyOne() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                attempts.add(pool.submit(() -> {
                    go.await();
                    return authService.setUpTerminal("654321", 1L, "10.1.1.1");
                }));
            }
            go.countDown();
            int succeeded = 0;
            for (Future<?> attempt : attempts) {
                try {
                    attempt.get(15, TimeUnit.SECONDS);
                    succeeded++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ConflictException.class)
                            .hasMessageContaining("already has its till");
                }
            }
            assertThat(succeeded).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM till_device WHERE outlet_id = 1 AND reset_at IS NULL", Long.class)).isEqualTo(1L);
    }

    @Test
    void twoResetsAtOnceResetOnceAndAnyOtherIsNotFound() throws Exception {
        setUpIPad("654321", 1L);
        long deviceId = activeDevice(1L);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> resets = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                resets.add(pool.submit(() -> {
                    go.await();
                    devices.reset(deviceId, new DashboardUser(true, null), "Head Office");
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> reset : resets) {
                try {
                    reset.get(15, TimeUnit.SECONDS);
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ResourceNotFoundException.class);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT reset_by FROM till_device WHERE id = ?", String.class, deviceId)).isEqualTo("Head Office");
        // And a new iPad can now be set up, once.
        setUpIPad("654321", 1L);
        call(post("/api/v1/auth/terminal-setup"), NO_TOKEN, "{\"pin\":\"654321\",\"outletId\":1}").andExpect(status().isConflict());
    }

    // ---- helpers ----

    private String setUpIPad(String managerPin, long outletId) throws Exception {
        return JsonPath.read(call(post("/api/v1/auth/terminal-setup"), NO_TOKEN,
                "{\"pin\":\"" + managerPin + "\",\"outletId\":" + outletId + "}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.deviceKey");
    }

    private String login(String deviceKey, String pin) throws Exception {
        return JsonPath.read(call(post("/api/v1/auth/login"), NO_TOKEN,
                "{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + deviceKey + "\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String approvalFor(String tillToken) throws Exception {
        return JsonPath.read(call(post("/api/v1/auth/special-access"), tillToken,
                "{\"pin\":\"654321\",\"operation\":\"DISCOUNT\"}").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private long activeDevice(long outletId) {
        return jdbc.queryForObject("SELECT id FROM till_device WHERE outlet_id = ? AND reset_at IS NULL", Long.class, outletId);
    }

    /** A till token signed like the real ones but naming no device (as before devices were named). */
    private String withoutDevice(Staff staff) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer("counterline-pos").id(UUID.randomUUID().toString())
                .subject(staff.getId().toString()).issuedAt(now).expiresAt(now.plus(Duration.ofHours(1)))
                .claim("name", staff.getFullName()).claim("role", staff.getRole().name()).claim("outlet", 1L)
                .claim("channel", "till").claim("sver", staff.getAuthSessionVersion()).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private int callStatus(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.with(remote());
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions callAs(MockHttpServletRequestBuilder request, RequestPostProcessor auth, String json) throws Exception {
        request.with(auth);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    /** Each request from its own address, so the per-address throttles do not interfere. */
    private static RequestPostProcessor remote() {
        return request -> {
            request.setRemoteAddr("10.40.0." + (int) (Math.random() * 200 + 1));
            return request;
        };
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-1").claim("role", "ADMIN").claim("name", "Head Office"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
