package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Failed logins, set-ups, pairings and sign-ups in a burst, against a database pool far smaller than the burst: recording
 * a failure must not need a second connection while the request holds one (a pool that runs dry would stall the whole
 * API), and a burst of parallel guesses must be held to the limit, exactly. Every wait is bounded.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=3000"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ThrottleConcurrencyIntegrationTest {

    private static final String CART = """
            {"stage":"CART","lines":[],"itemCount":0,"subtotal":0,"discount":0,"total":0,"memberName":null}""";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    LoginAttemptLimiter limiter;

    private Long cashierId;

    @BeforeEach
    void aCashierAndAManager() {
        clear();
        jdbc.update("DELETE FROM staff WHERE full_name LIKE 'Burst %'");
        cashierId = staffRepository.save(new Staff("Burst Cashier", pinHasher.hash("135790"), StaffRole.STAFF, 1L)).getId();
        staffRepository.save(new Staff("Burst Manager", pinHasher.hash("246801"), StaffRole.MANAGER, 1L));
    }

    @AfterEach
    void clear() {
        jdbc.update("DELETE FROM login_throttle");
        jdbc.update("DELETE FROM customer_display WHERE outlet_id = 1");
        jdbc.update("DELETE FROM till_device WHERE outlet_id = 1");
        jdbc.update("DELETE FROM customer WHERE phone LIKE '+60112233%' OR first_name = 'Burst'");
        jdbc.update("DELETE FROM staff WHERE full_name LIKE 'Burst %'");
    }

    @Test
    void aBurstOfWrongPasswordsFromManyClientsDoesNotRunThePoolDry() throws Exception {
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int n = i;
            attempts.add(() -> dashboardLogin("10.88.0." + (n + 1), "burst-" + n + "@throttle.test", "wrong-password-" + n));
        }

        long started = System.nanoTime();
        List<MockHttpServletResponse> answers = together(attempts);

        assertThat(answers).extracting(MockHttpServletResponse::getStatus).containsOnly(401);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)).isLessThan(20);
    }

    @Test
    void aBurstOfParallelGuessesAtOneAccountOnOneClientGetsExactlyTheLimitAndIsThenLocked() throws Exception {
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            attempts.add(() -> dashboardLogin("10.88.1.1", "victim@throttle.test", "guess-" + n));
        }

        List<MockHttpServletResponse> answers = together(attempts);

        // However many arrive at once, only the limit's worth are ever checked; the rest are turned away.
        assertThat(count(answers, 401)).isEqualTo(limiter.maxFailures() - 1);
        assertThat(count(answers, 429)).isEqualTo(answers.size() - (limiter.maxFailures() - 1));
        // (The tenth wrong answer is the one that locks, so it is already a 429.)
        assertThat(limiter.lockedForSeconds(DashboardAuthService.clientAccountKey("victim@throttle.test", "10.88.1.1"))).isPositive();
        assertThat(jdbc.queryForObject("SELECT pending FROM login_throttle WHERE throttle_key = ?", Integer.class,
                DashboardAuthService.clientAccountKey("victim@throttle.test", "10.88.1.1"))).isZero();
    }

    @Test
    void aBurstOfWrongSetUpPinsIsHeldToTheLimit() throws Exception {
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            attempts.add(() -> mockMvc.perform(post("/api/v1/auth/terminal-setup").with(request -> {
                request.setRemoteAddr("10.89.0." + (n + 1));
                return request;
            }).contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"000000\",\"outletId\":1}")).andReturn().getResponse());
        }

        List<MockHttpServletResponse> answers = together(attempts);

        assertThat(count(answers, 403)).isEqualTo(limiter.maxFailures() - 1);
        assertThat(count(answers, 429)).isEqualTo(answers.size() - (limiter.maxFailures() - 1));
        assertThat(limiter.lockedForSeconds(PinPad.SETUP.key(1L))).isPositive();
        // A right manager PIN is refused while the pad is locked, like any other.
        mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"246801\",\"outletId\":1}")).andReturn().getResponse().getStatus();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM till_device WHERE outlet_id = 1", Integer.class)).isZero();
    }

    @Test
    void aBurstOfWrongTillPinsIsHeldToTheLimitAndARightPinIsStillRefusedWhileLocked() throws Exception {
        String key = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"246801\",\"outletId\":1}")).andReturn().getResponse().getContentAsString(), "$.deviceKey");
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int n = i;
            attempts.add(() -> mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"pin\":\"" + "%06d".formatted(n) + "\",\"deviceKey\":\"" + key + "\"}")).andReturn().getResponse());
        }

        List<MockHttpServletResponse> answers = together(attempts);

        assertThat(count(answers, 401)).isEqualTo(limiter.maxFailures() - 1);
        assertThat(count(answers, 429)).isEqualTo(answers.size() - (limiter.maxFailures() - 1));
        assertThat(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"135790\",\"deviceKey\":\"" + key + "\"}")).andReturn().getResponse().getStatus()).isEqualTo(429);
    }

    @Test
    void aBurstOfWrongPairingCodesFromOneClientIsHeldToTheLimit() throws Exception {
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            int n = i;
            attempts.add(() -> connect("10.90.0.1", "%06d".formatted(n)));
        }

        List<MockHttpServletResponse> answers = together(attempts);

        assertThat(count(answers, 422)).isEqualTo(limiter.maxFailures() - 1);
        assertThat(count(answers, 429)).isEqualTo(answers.size() - (limiter.maxFailures() - 1));
    }

    @Test
    void pairingCodesGuessedFromManyClientsAreHeldByTheWiderLimitAndItNeverShowsWhetherACodeIsRight() throws Exception {
        String created = mockMvc.perform(post("/api/v1/displays").with(jwt().jwt(token -> token.claim("outlet", 1L)
                .subject(cashierId.toString())))).andReturn().getResponse().getContentAsString();
        String realCode = JsonPath.read(created, "$.accessCode");
        int ceiling = limiter.maxFailures() * 10;
        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 0; i < ceiling + 20; i++) {
            int n = i;
            attempts.add(() -> connect("10.91." + (n / 200) + "." + (n % 200 + 1), "%06d".formatted(900_000 + n)));
        }

        List<MockHttpServletResponse> answers = together(attempts);

        // One wrong guess from each of many clients: no client is near its own limit, but the guessing as a whole is held.
        assertThat(count(answers, 422)).isEqualTo(ceiling - 1);
        assertThat(count(answers, 429)).isEqualTo(answers.size() - (ceiling - 1));
        // While it is held, the right code from a fresh client is turned away exactly like a wrong one: nothing is revealed.
        MockHttpServletResponse right = connect("10.92.0.1", realCode);
        MockHttpServletResponse wrong = connect("10.92.0.2", "999999");
        assertThat(right.getStatus()).isEqualTo(429);
        assertThat(wrong.getStatus()).isEqualTo(429);
        assertThat(right.getContentAsString().replaceAll("\\d+", "#")).isEqualTo(wrong.getContentAsString().replaceAll("\\d+", "#"));
        assertThat(right.getContentAsString()).doesNotContain(realCode);
    }

    @Test
    void aDisplayTakesFiveSignUpsPerWindowEvenWhenTheyArriveTogetherAndAnUnsuccessfulOneCostsNothing() throws Exception {
        String token = connectedDisplayWithASaleOpen();
        // A number that is already a member: refused, and not counted as one of the five.
        signUp(token, "0112233400").andExpect(201);
        jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE 'display-signup:%'");
        for (int repeat = 0; repeat < 3; repeat++) {
            signUp(token, "0112233400").andExpect(409);
        }
        assertThat(signUpsCounted()).isZero();

        List<Callable<MockHttpServletResponse>> attempts = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String phone = "01122334%02d".formatted(i);
            attempts.add(() -> signUpRaw(token, phone));
        }
        List<MockHttpServletResponse> answers = together(attempts);

        assertThat(count(answers, 201)).isEqualTo(5);
        assertThat(count(answers, 429)).isEqualTo(7);
        assertThat(signUpsCounted()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customer WHERE phone LIKE '+60112233%'", Integer.class)).isEqualTo(6);

        // A new window starts the count again.
        jdbc.update("UPDATE login_throttle SET last_failed_at = now() - interval '10 minutes' WHERE throttle_key LIKE 'display-signup:%'");
        signUp(token, "0112233499").andExpect(201);
        assertThat(signUpsCounted()).isEqualTo(1);
    }

    // ---- helpers ----

    private String connectedDisplayWithASaleOpen() throws Exception {
        String created = mockMvc.perform(post("/api/v1/displays").with(jwt().jwt(token -> token.claim("outlet", 1L)
                .subject(cashierId.toString())))).andReturn().getResponse().getContentAsString();
        int displayId = JsonPath.read(created, "$.id");
        String connected = connect("10.93.0.1", JsonPath.read(created, "$.accessCode")).getContentAsString();
        mockMvc.perform(put("/api/v1/displays/" + displayId + "/cart").with(jwt().jwt(token -> token.claim("outlet", 1L)
                        .subject(cashierId.toString()))).contentType(MediaType.APPLICATION_JSON).content(CART)).andReturn();
        return JsonPath.read(connected, "$.token");
    }

    private int signUpsCounted() {
        return jdbc.queryForList("SELECT failures FROM login_throttle WHERE throttle_key LIKE 'display-signup:%'", Integer.class)
                .stream().findFirst().orElse(0);
    }

    private SignUp signUp(String token, String phone) throws Exception {
        return new SignUp(signUpRaw(token, phone));
    }

    private record SignUp(MockHttpServletResponse response) {

        SignUp andExpect(int status) throws Exception {
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
            return this;
        }
    }

    private MockHttpServletResponse signUpRaw(String token, String phone) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/display/members").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\",\"firstName\":\"Burst\",\"lastName\":\"Signup\"}");
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse connect(String address, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/display/connect").with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content("{\"accessCode\":\"" + code + "\"}")).andReturn().getResponse();
    }

    private MockHttpServletResponse dashboardLogin(String address, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/auth/login").with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")).andReturn().getResponse();
    }

    private static long count(List<MockHttpServletResponse> answers, int status) {
        return answers.stream().filter(answer -> answer.getStatus() == status).count();
    }

    /** Runs all at once and collects the answers; every wait is bounded, so a stuck request fails instead of hanging. */
    private static <T> List<T> together(List<Callable<T>> calls) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            List<Future<T>> running = new ArrayList<>();
            for (Callable<T> call : calls) {
                running.add(pool.submit(() -> {
                    assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                    return call.call();
                }));
            }
            go.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> one : running) {
                answers.add(one.get(40, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
