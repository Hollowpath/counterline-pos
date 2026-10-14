package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end PIN login against PostgreSQL: real hashing, real token signing and validation.
 * The throttle (per outlet) is cleared before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthApiIntegrationTest {

    private static final String CASHIER_PIN = "123456";
    private static final String MANAGER_PIN = "654321";
    private static final String INACTIVE_PIN = "222222";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    JdbcTemplate jdbc;

    private Staff cashier;

    /** The iPad the tests log in on, set up at outlet 1. */
    private String deviceKey;

    @BeforeEach
    void createStaff() throws Exception {
        // Also clears the orders and iPads that reference staff.
        jdbc.execute("TRUNCATE login_throttle, staff RESTART IDENTITY CASCADE");
        cashier = staffRepository.save(new Staff("Test Cashier", pinHasher.hash(CASHIER_PIN), StaffRole.STAFF, 1L));
        staffRepository.save(new Staff("Test Manager", pinHasher.hash(MANAGER_PIN), StaffRole.MANAGER, 1L));
        Staff leaver = new Staff("Former Staff", pinHasher.hash(INACTIVE_PIN), StaffRole.STAFF, 1L);
        leaver.deactivate();
        staffRepository.save(leaver);
        deviceKey = JsonPath.read(setUp(MANAGER_PIN, "10.1.0.100").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.deviceKey");
    }

    @Test
    void validPinReturnsATokenAndTheStaffMember() throws Exception {
        login(MANAGER_PIN, "10.1.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(not(emptyString())))
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.staff.fullName").value("Test Manager"))
                .andExpect(jsonPath("$.staff.role").value("MANAGER"))
                .andExpect(jsonPath("$.outlet.id").value(1))
                .andExpect(jsonPath("$.outlet.name").value("Outlet 1"));
    }

    @Test
    void theTokenCarriesTheTerminalsOutlet() throws Exception {
        String token = tokenFor(CASHIER_PIN, "10.1.0.10");
        String payload = new String(java.util.Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        org.assertj.core.api.Assertions.assertThat(JsonPath.<Integer>read(payload, "$.outlet")).isEqualTo(1);
    }

    @Test
    void loginNeedsAnIPadThatIsStillSetUp() throws Exception {
        login(CASHIER_PIN, "not-a-device-key", "10.1.0.11")
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("This iPad has been reset. A manager needs to set it up again."));
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"pin\":\"123456\"}"))
                .andExpect(status().isBadRequest());

        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Head Office'");
        login(CASHIER_PIN, "10.1.0.11").andExpect(status().isGone());
    }

    @Test
    void aManagersPinSetsAnIPadUpAtTheirOutletOnce() throws Exception {
        // One till per outlet: until the first is reset, no other can be set up there.
        setUp(MANAGER_PIN, "10.1.0.12").andExpect(status().isConflict());
        jdbc.update("UPDATE till_device SET reset_at = now(), reset_by = 'Head Office'");

        String body = setUp(MANAGER_PIN, "10.1.0.12").andExpect(status().isOk())
                .andExpect(jsonPath("$.outlet.id").value(1))
                .andExpect(jsonPath("$.outlet.name").value("Outlet 1"))
                .andExpect(jsonPath("$.label").value("iPad 2"))
                .andReturn().getResponse().getContentAsString();
        String secondKey = JsonPath.read(body, "$.deviceKey");
        org.assertj.core.api.Assertions.assertThat(secondKey).isNotEqualTo(deviceKey).hasSizeGreaterThan(40);
        // Only its hash is stored.
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM till_device WHERE key_hash = ?", Long.class, secondKey)).isZero();
        login(CASHIER_PIN, secondKey, "10.1.0.12").andExpect(status().isOk());

        setUp(CASHIER_PIN, "10.1.0.13").andExpect(status().isForbidden());
    }

    @Test
    void aTokenWithoutAnOutletIsRejected() throws Exception {
        // Issued before outlets existed: the terminal must log in again.
        mockMvc.perform(get("/api/v1/register").with(org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.jwt().jwt(token -> token.subject(cashier.getId().toString()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void issuedTokenAuthenticatesApiRequests() throws Exception {
        String token = tokenFor(CASHIER_PIN, "10.1.0.2");

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Test Cashier"))
                .andExpect(jsonPath("$.role").value("STAFF"));

        mockMvc.perform(get("/api/v1/catalog/tabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPinIsRejectedWithoutSayingWhy() throws Exception {
        login("000000", "10.1.0.3")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Login failed"))
                .andExpect(jsonPath("$.detail").value("Invalid PIN"));
    }

    @Test
    void deactivatedStaffCannotLogIn() throws Exception {
        login(INACTIVE_PIN, "10.1.0.4").andExpect(status().isUnauthorized());
    }

    @Test
    void pinMustBeExactlySixDigits() throws Exception {
        login("12ab", "10.1.0.5")
                .andExpect(status().isBadRequest());
    }

    @Test
    void apiRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/v1/catalog/tabs"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.title").value("Authentication required"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = tokenFor(CASHIER_PIN, "10.1.0.6");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        mockMvc.perform(get("/api/v1/catalog/tabs").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenOfAStaffMemberDeactivatedAfterLoginNoLongerResolves() throws Exception {
        String token = tokenFor(CASHIER_PIN, "10.1.0.7");
        cashier.deactivate();
        staffRepository.save(cashier);

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theTenthWrongPinInARowLocksTheOutletsPinLoginEvenForACorrectPin() throws Exception {
        for (int i = 0; i < 9; i++) {
            login("000000", "10.1.0.8").andExpect(status().isUnauthorized());
        }
        login("000000", "10.1.0.8")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "300"))
                .andExpect(jsonPath("$.title").value("Too many attempts"))
                .andExpect(jsonPath("$.detail").value("Too many incorrect PINs. Try again in 300 seconds, "
                        + "or ask a manager to unlock it on the dashboard."));

        // The outlet's PIN pad is locked, from wherever it is tried.
        login(CASHIER_PIN, "10.1.0.9").andExpect(status().isTooManyRequests());
    }

    @Test
    void outletsArePublicForTheSetUpAndLoginScreens() throws Exception {
        mockMvc.perform(get("/api/v1/outlets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("O1"))
                .andExpect(jsonPath("$[0].name").value("Outlet 1"));
        mockMvc.perform(get("/api/v1/outlets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Outlet 1"));
        mockMvc.perform(get("/api/v1/outlets/999")).andExpect(status().isNotFound());
    }

    private ResultActions login(String pin, String clientAddress) throws Exception {
        return login(pin, deviceKey, clientAddress);
    }

    private ResultActions login(String pin, String key, String clientAddress) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + key + "\"}"));
    }

    private ResultActions setUp(String pin, String clientAddress) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/terminal-setup")
                .with(request -> {
                    request.setRemoteAddr(clientAddress);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\",\"outletId\":1}"));
    }

    private String tokenFor(String pin, String clientAddress) throws Exception {
        String body = login(pin, clientAddress)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
