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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logging out ends the presented token and only that one (staff and head office, till and dashboard); a head-office
 * account's password change or deactivation ends all its tokens for good; the dashboard opens only to a dashboard
 * login, never to a manager's PIN at the till.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class LogoutAndAdminSessionIntegrationTest {

    private static final String OUTLETS = "/api/v1/dashboard/outlets";

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String password = "pw-" + UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    PinHasher pinHasher;
    @Autowired
    TokenService tokenService;
    @Autowired
    JwtEncoder encoder;

    private Staff manager;
    private Staff cashier;
    private AdminUser admin;
    private String deviceKey;
    private String managerEmail;
    private String adminEmail;

    @BeforeEach
    void anOutletWithATillAManagerACashierAndHeadOffice() throws Exception {
        jdbc.update("DELETE FROM login_throttle");
        managerEmail = "mgr-" + suffix + "@session.test";
        adminEmail = "head-" + suffix + "@session.test";
        manager = new Staff("Session Manager " + suffix, pinHasher.hash("357951"), StaffRole.MANAGER, 1L);
        manager.setDashboardLogin(managerEmail, passwordEncoder.encode(password));
        manager = staffRepository.save(manager);
        cashier = staffRepository.save(new Staff("Session Cashier " + suffix, pinHasher.hash("159753"), StaffRole.STAFF, 1L));
        admin = adminRepository.save(new AdminUser(adminEmail, "Head Office", passwordEncoder.encode(password), Instant.now()));
        jdbc.update("DELETE FROM till_device WHERE outlet_id = 1");
        deviceKey = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"357951\",\"outletId\":1}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.deviceKey");
    }

    @AfterEach
    void removeWhatWasCreated() {
        jdbc.update("DELETE FROM revoked_token");
        jdbc.update("DELETE FROM till_device WHERE outlet_id = 1");
        jdbc.update("DELETE FROM staff WHERE full_name LIKE ?", "Session %" + suffix);
        jdbc.update("DELETE FROM admin_user WHERE email = ?", adminEmail);
        jdbc.update("DELETE FROM login_throttle");
    }

    // ---- Logout ends the presented token only ----

    @Test
    void aTillLogoutEndsThatTokenAndLeavesTheSameStaffMembersOtherSessionAlone() throws Exception {
        String first = tillLogin("159753");
        String second = tillLogin("159753");
        int version = jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, cashier.getId());
        call(get("/api/v1/auth/me"), first).andExpect(status().isOk());
        call(get("/api/v1/auth/me"), second).andExpect(status().isOk());

        call(post("/api/v1/auth/logout"), first).andExpect(status().isNoContent());

        call(get("/api/v1/auth/me"), first).andExpect(status().isUnauthorized());
        call(post("/api/v1/auth/logout"), first).andExpect(status().isUnauthorized());
        call(get("/api/v1/auth/me"), second).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, cashier.getId())).isEqualTo(version);
        // Another staff member's token is not affected either.
        String managerToken = tillLogin("357951");
        call(post("/api/v1/auth/logout"), second).andExpect(status().isNoContent());
        call(get("/api/v1/auth/me"), managerToken).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_token", Integer.class)).isEqualTo(2);
    }

    @Test
    void aDashboardLogoutEndsThatTokenForAManagerAndForHeadOfficeAndNothingElse() throws Exception {
        for (String email : new String[] {managerEmail, adminEmail}) {
            String first = dashboardLogin(email);
            String second = dashboardLogin(email);
            call(get(OUTLETS), first).andExpect(status().isOk());

            call(post("/api/v1/dashboard/auth/logout"), first).andExpect(status().isNoContent());

            call(get(OUTLETS), first).andExpect(status().isUnauthorized());
            call(post("/api/v1/dashboard/auth/logout"), first).andExpect(status().isUnauthorized());
            call(get(OUTLETS), second).andExpect(status().isOk());
        }
        // A manager's till session was never touched by their dashboard logout.
        call(get("/api/v1/auth/me"), tillLogin("357951")).andExpect(status().isOk());
    }

    @Test
    void aLoggedOutTokenStaysRefusedAndTheRecordIsForgottenOnceTheTokenWouldHaveExpiredAnyway() throws Exception {
        String token = tillLogin("159753");
        call(post("/api/v1/auth/logout"), token).andExpect(status().isNoContent());
        jdbc.update("INSERT INTO revoked_token (token_id, expires_at) VALUES ('long-gone', ?)", Timestamp.from(Instant.now().minus(Duration.ofHours(1))));

        call(post("/api/v1/auth/logout"), tillLogin("159753")).andExpect(status().isNoContent());

        call(get("/api/v1/auth/me"), token).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_token WHERE token_id = 'long-gone'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM revoked_token", Integer.class)).isEqualTo(2);
    }

    // ---- Head-office sessions ----

    @Test
    void aPasswordChangeEndsEveryHeadOfficeTokenAndANewLoginWorks() throws Exception {
        String first = dashboardLogin(adminEmail);
        String second = dashboardLogin(adminEmail);
        call(get(OUTLETS), first).andExpect(status().isOk());

        AdminUser account = adminRepository.findById(admin.getId()).orElseThrow();
        account.changePassword(passwordEncoder.encode("a-new-" + password));
        adminRepository.save(account);

        call(get(OUTLETS), first).andExpect(status().isUnauthorized());
        call(get(OUTLETS), second).andExpect(status().isUnauthorized());
        dashboardLoginWith(adminEmail, password).andExpect(status().isUnauthorized());
        String fresh = JsonPath.read(dashboardLoginWith(adminEmail, "a-new-" + password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
        call(get(OUTLETS), fresh).andExpect(status().isOk());
        call(get(OUTLETS), first).andExpect(status().isUnauthorized());
    }

    @Test
    void aDeactivatedHeadOfficeAccountLosesItsTokensForGoodAlsoIfItIsReactivated() throws Exception {
        String token = dashboardLogin(adminEmail);
        call(get(OUTLETS), token).andExpect(status().isOk());

        AdminUser account = adminRepository.findById(admin.getId()).orElseThrow();
        account.deactivate();
        adminRepository.save(account);
        call(get(OUTLETS), token).andExpect(status().isUnauthorized());
        dashboardLoginWith(adminEmail, password).andExpect(status().isUnauthorized());

        account = adminRepository.findById(admin.getId()).orElseThrow();
        account.reactivate();
        adminRepository.save(account);
        call(get(OUTLETS), token).andExpect(status().isUnauthorized());
        call(get(OUTLETS), dashboardLogin(adminEmail)).andExpect(status().isOk());
    }

    @Test
    void aHeadOfficeTokenFromBeforeSessionVersionsExistedIsNeverAccepted() throws Exception {
        // The same token as issued today, minus the session version and id.
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("counterline-pos").subject("admin-" + admin.getId())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plus(Duration.ofHours(1)))
                .claim("role", "ADMIN").claim("channel", "dashboard");
        String oldStyle = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
        call(get(OUTLETS), oldStyle).andExpect(status().isUnauthorized());
        // With an id and the right version it is accepted: only the missing parts were the problem.
        String complete = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                claims.id(UUID.randomUUID().toString()).claim("sver", admin.getAuthSessionVersion()).build())).getTokenValue();
        call(get(OUTLETS), complete).andExpect(status().isOk());
    }

    // ---- The dashboard needs a dashboard login ----

    @Test
    void aManagersPinAtTheTillNeverOpensTheDashboardButTheirDashboardLoginDoes() throws Exception {
        String pinSession = tillLogin("357951");

        call(get(OUTLETS), pinSession).andExpect(status().isForbidden());
        call(get("/api/v1/dashboard/pin-locks"), pinSession).andExpect(status().isForbidden());
        call(get("/api/v1/dashboard/devices"), pinSession).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/dashboard/customers/1/points").header(HttpHeaders.AUTHORIZATION, "Bearer " + pinSession)
                .contentType(MediaType.APPLICATION_JSON).content("{\"points\":5,\"reason\":\"Try\"}")).andExpect(status().isForbidden());
        call(get("/api/v1/dashboard/admin/staff"), pinSession).andExpect(status().isForbidden());

        String dashboard = dashboardLogin(managerEmail);
        call(get(OUTLETS), dashboard).andExpect(status().isOk());
        call(get("/api/v1/dashboard/pin-locks"), dashboard).andExpect(status().isOk());
        // And the other way round: a dashboard login is not a till session.
        call(get("/api/v1/auth/me"), dashboard).andExpect(status().isForbidden());
        // Nor is the manager's approval token a dashboard session.
        String approvalToken = JsonPath.read(mockMvc.perform(post("/api/v1/auth/special-access")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tillLogin("159753")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"357951\",\"operation\":\"VOID\"}")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(), "$.token");
        call(get(OUTLETS), approvalToken).andExpect(status().isForbidden());
    }

    // ---- helpers ----

    private String tillLogin(String pin) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + deviceKey + "\"}")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String dashboardLogin(String email) throws Exception {
        return JsonPath.read(dashboardLoginWith(email, password).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.token");
    }

    private ResultActions dashboardLoginWith(String email, String pass) throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + pass + "\"}"));
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
