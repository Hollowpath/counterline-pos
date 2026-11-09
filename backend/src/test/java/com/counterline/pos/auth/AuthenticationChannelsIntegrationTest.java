package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CodeQL alert java/spring-disabled-csrf-protection, assessed against how requests can actually authenticate.
 *
 * <p>CSRF protection is disabled in {@code SecurityConfig}. That is only defensible while the one thing that
 * authenticates a request is a credential the browser does not attach by itself. These tests pin that down with the
 * real security filter chain and synthetic data: a valid token in a cookie, HTTP Basic (including real credentials),
 * or a token in the query string or a form body must not authenticate a mutation, an Authorization bearer header
 * must, and logging in must not set a cookie or start a server-side session. Tokens and passwords are never printed.
 * If any of these tests ever has to change because a cookie, Basic, form login or another channel was added, the
 * CSRF decision must be reviewed first (see docs/engineering-readiness.md).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuthenticationChannelsIntegrationTest {

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String password = "pw-" + UUID.randomUUID();
    private final String phone = String.format("0123%06d", Math.abs(suffix.hashCode()) % 1_000_000);

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

    private String adminEmail;
    private String deviceKey;
    private String tillToken;
    private MvcResult terminalSetup;
    private String dashboardToken;

    @BeforeEach
    void aTillAManagerAndHeadOfficeAreSignedIn() throws Exception {
        jdbc.update("DELETE FROM login_throttle");
        adminEmail = "chan-" + suffix + "@channels.test";
        staffRepository.save(new Staff("Channels Manager " + suffix, pinHasher.hash("357951"), StaffRole.MANAGER, 1L));
        adminRepository.save(new AdminUser(adminEmail, "Head Office", passwordEncoder.encode(password), Instant.now()));
        jdbc.update("DELETE FROM till_device WHERE outlet_id = 1");
        terminalSetup = mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"357951\",\"outletId\":1}")).andExpect(status().isOk()).andReturn();
        deviceKey = JsonPath.read(terminalSetup.getResponse().getContentAsString(), "$.deviceKey");
        tillToken = JsonPath.read(tillLogin().getResponse().getContentAsString(), "$.token");
        dashboardToken = JsonPath.read(dashboardLogin().getResponse().getContentAsString(), "$.token");
    }

    @AfterEach
    void removeWhatWasCreated() {
        jdbc.update("DELETE FROM revoked_token");
        jdbc.update("DELETE FROM customer WHERE first_name = 'ChannelProbe'");
        jdbc.update("DELETE FROM till_device WHERE outlet_id = 1");
        jdbc.update("DELETE FROM staff WHERE full_name LIKE ?", "Channels %" + suffix);
        jdbc.update("DELETE FROM admin_user WHERE email = ?", adminEmail);
        jdbc.update("DELETE FROM login_throttle");
    }

    // ---- the two representative protected mutations -----------------------------------------------------------

    /** A till mutation: adding a member (till channel). */
    private MockHttpServletRequestBuilder addMember() {
        return post("/api/v1/customers").contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"ChannelProbe\",\"lastName\":\"" + suffix + "\",\"phone\":\"" + phone + "\"}");
    }

    private long members() {
        return jdbc.queryForObject("SELECT count(*) FROM customer WHERE first_name = 'ChannelProbe'", Long.class);
    }

    /** A dashboard mutation: deactivating a member (manager or head office, dashboard channel). */
    private MockHttpServletRequestBuilder deactivate(long customerId) {
        return post("/api/v1/customers/" + customerId + "/deactivate");
    }

    private long aMemberCreatedByTheTill() throws Exception {
        String body = mockMvc.perform(addMember().header(HttpHeaders.AUTHORIZATION, "Bearer " + tillToken))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private boolean active(long customerId) {
        return jdbc.queryForObject("SELECT deactivated_at IS NULL FROM customer WHERE id = ?", Boolean.class, customerId);
    }

    // ---- cookies alone never authenticate ----------------------------------------------------------------------

    private Cookie[] cookiesCarrying(String token) {
        // Every name a framework, a convention or a developer might plausibly read a credential from.
        return new Cookie[] {new Cookie("Authorization", "Bearer " + token), new Cookie("Authorization", token),
                new Cookie("token", token), new Cookie("access_token", token), new Cookie("jwt", token),
                new Cookie("bearer", token), new Cookie("JSESSIONID", token), new Cookie("SESSION", token),
                new Cookie("XSRF-TOKEN", token)};
    }

    @Test
    void validTokensInCookiesAloneCannotAuthenticateAMutation() throws Exception {
        long before = members();
        mockMvc.perform(addMember().cookie(cookiesCarrying(tillToken))).andExpect(status().isUnauthorized());
        mockMvc.perform(addMember().cookie(cookiesCarrying(dashboardToken))).andExpect(status().isUnauthorized());
        assertThat(members()).as("no member was created by a cookie-only request").isEqualTo(before);

        long member = aMemberCreatedByTheTill();
        mockMvc.perform(deactivate(member).cookie(cookiesCarrying(dashboardToken))).andExpect(status().isUnauthorized());
        mockMvc.perform(deactivate(member).cookie(cookiesCarrying(tillToken))).andExpect(status().isUnauthorized());
        assertThat(active(member)).as("the member is still active").isTrue();
    }

    // ---- no other channel replaces the Authorization bearer header ----------------------------------------------

    private static String basic(String user, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void httpBasicCannotReplaceTheBearerHeader() throws Exception {
        long before = members();
        // Real head-office credentials in Basic form, and the token used as a Basic username or password.
        for (String header : new String[] {basic(adminEmail, password), basic(tillToken, ""), basic("user", tillToken),
                basic(adminEmail, dashboardToken)}) {
            mockMvc.perform(addMember().header(HttpHeaders.AUTHORIZATION, header)).andExpect(status().isUnauthorized());
        }
        assertThat(members()).isEqualTo(before);

        long member = aMemberCreatedByTheTill();
        for (String header : new String[] {basic(adminEmail, password), basic("user", dashboardToken)}) {
            mockMvc.perform(deactivate(member).header(HttpHeaders.AUTHORIZATION, header)).andExpect(status().isUnauthorized());
        }
        assertThat(active(member)).isTrue();
    }

    @Test
    void queryStringAndFormBodyTokensCannotReplaceTheBearerHeader() throws Exception {
        long before = members();
        for (String name : new String[] {"access_token", "token", "jwt", "bearer"}) {
            mockMvc.perform(addMember().param(name, tillToken)).andExpect(status().isUnauthorized());
        }
        // The query string proper (a URI query token is only ever considered by a resolver for reads, so a read is checked too).
        mockMvc.perform(post("/api/v1/customers?access_token=" + tillToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"ChannelProbe\",\"lastName\":\"" + suffix + "\",\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers?field=NAME&q=probe&access_token=" + tillToken)).andExpect(status().isUnauthorized());
        // A form-encoded body carrying the token (the OAuth 2.0 form-body method), alone and beside a JSON-less POST.
        mockMvc.perform(post("/api/v1/customers").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("access_token=" + tillToken + "&firstName=ChannelProbe&phone=" + phone))
                .andExpect(status().isUnauthorized());
        assertThat(members()).isEqualTo(before);

        long member = aMemberCreatedByTheTill();
        mockMvc.perform(deactivate(member).param("access_token", dashboardToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/customers/" + member + "/deactivate").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("access_token=" + dashboardToken)).andExpect(status().isUnauthorized());
        assertThat(active(member)).isTrue();
    }

    @Test
    void equivalentRequestsWithABearerHeaderSucceed() throws Exception {
        long before = members();
        long member = aMemberCreatedByTheTill(); // till token in the Authorization header: 201
        assertThat(members()).isEqualTo(before + 1);
        mockMvc.perform(deactivate(member).header(HttpHeaders.AUTHORIZATION, "Bearer " + dashboardToken))
                .andExpect(status().isOk());
        assertThat(active(member)).isFalse();
    }

    // ---- logging in sets no cookie and starts no session --------------------------------------------------------

    private MvcResult tillLogin() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"357951\",\"deviceKey\":\"" + deviceKey + "\"}")).andExpect(status().isOk()).andReturn();
    }

    private MvcResult dashboardLogin() throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + adminEmail + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
    }

    private void assertNoCookieAndNoSession(MvcResult result, String what) {
        assertThat(result.getResponse().getCookies()).as(what + ": no cookie issued").isEmpty();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).as(what + ": no Set-Cookie header").isEmpty();
        assertThat(result.getRequest().getSession(false)).as(what + ": no server-side session created").isNull();
    }

    @Test
    void loginIssuesNoAuthenticationCookieAndCreatesNoServerSideSession() throws Exception {
        assertNoCookieAndNoSession(tillLogin(), "till PIN login");
        assertNoCookieAndNoSession(dashboardLogin(), "dashboard login");
        assertNoCookieAndNoSession(terminalSetup, "terminal setup");
        // Authenticated requests do not start one either.
        assertNoCookieAndNoSession(mockMvc.perform(addMember().header(HttpHeaders.AUTHORIZATION, "Bearer " + tillToken))
                .andExpect(status().isCreated()).andReturn(), "an authenticated mutation");
        // And the token is the only thing that carried the identity: without it the same call is anonymous.
        Function<MockHttpServletRequestBuilder, Integer> anonymous = b -> {
            try {
                return mockMvc.perform(b).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(anonymous.apply(addMember())).isEqualTo(401);
    }
}
