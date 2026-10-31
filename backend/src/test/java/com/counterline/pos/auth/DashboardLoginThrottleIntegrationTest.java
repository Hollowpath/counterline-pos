package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wrong dashboard passwords are counted per account on the client they come from (the e-mail typed, real or not), per
 * client address as a wider net, and per account across all clients as a much wider one: one client's failures lock the
 * account only for that client, never for its real user elsewhere nor a different account sharing the address; an
 * address with many failures across accounts, and an account attacked from very many clients, stay limited. A right
 * password never clears anyone's failures.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DashboardLoginThrottleIntegrationTest {

    private static final String ADDRESS = "10.77.0.1";

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String password = "pw-" + UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    LoginAttemptLimiter limiter;

    private String alice;
    private String bob;

    @BeforeEach
    void twoAccounts() {
        clearThrottle();
        alice = "alice-" + suffix + "@throttle.test";
        bob = "bob-" + suffix + "@throttle.test";
        adminRepository.save(new AdminUser(alice, "Alice", passwordEncoder.encode(password), Instant.now()));
        adminRepository.save(new AdminUser(bob, "Bob", passwordEncoder.encode(password), Instant.now()));
    }

    @AfterEach
    void tidy() {
        clearThrottle();
        jdbc.update("DELETE FROM admin_user WHERE email IN (?, ?)", alice, bob);
    }

    private void clearThrottle() {
        jdbc.update("DELETE FROM login_throttle WHERE throttle_key LIKE 'dashboard%'");
    }

    @Test
    void failuresForANonexistentAccountDoNotBlockAValidAccountOnTheSameAddress() throws Exception {
        String ghost = "ghost-" + suffix + "@throttle.test";
        int limit = limiter.maxFailures();
        for (int attempt = 1; attempt < limit; attempt++) {
            login(ADDRESS, ghost, "wrong-" + attempt).andExpect(status().isUnauthorized());
        }
        // The tenth failure for that e-mail locks that e-mail...
        login(ADDRESS, ghost, "wrong-last").andExpect(status().isTooManyRequests());
        login(ADDRESS, ghost, "wrong-again").andExpect(status().isTooManyRequests());

        // ...but not the real account that shares the address.
        login(ADDRESS, alice, password).andExpect(status().isOk());
        login(ADDRESS, bob, password).andExpect(status().isOk());
    }

    @Test
    void failuresForOneRealAccountLockThatAccountOnlyOnTheClientTheyCameFrom() throws Exception {
        for (int attempt = 1; attempt < limiter.maxFailures(); attempt++) {
            login(ADDRESS, alice, "wrong-" + attempt).andExpect(status().isUnauthorized());
        }
        login(ADDRESS, alice, "wrong-last").andExpect(status().isTooManyRequests());

        // Alice is limited from that client, even with her right password; Bob is unaffected, and so is Alice elsewhere.
        login(ADDRESS, alice, password).andExpect(status().isTooManyRequests());
        login(ADDRESS, bob, password).andExpect(status().isOk());
        login("10.77.0.2", alice, password).andExpect(status().isOk());

        // Her success elsewhere cleared nothing: the client that failed is still locked out of her account.
        login(ADDRESS, alice, password).andExpect(status().isTooManyRequests());
        assertThat(jdbc.queryForObject("SELECT failures FROM login_throttle WHERE throttle_key = ?", Integer.class,
                DashboardAuthService.clientAccountKey(alice, ADDRESS))).isEqualTo(limiter.maxFailures());
    }

    @Test
    void aSuccessGivesBackItsOwnAttemptAndLeavesTheCountsOfOtherClientsAlone() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            login(ADDRESS, alice, "wrong-" + attempt).andExpect(status().isUnauthorized());
        }

        login("10.77.0.2", alice, password).andExpect(status().isOk());
        login(ADDRESS, alice, password).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT failures FROM login_throttle WHERE throttle_key = ?", Integer.class,
                DashboardAuthService.clientAccountKey(alice, ADDRESS))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT failures FROM login_throttle WHERE throttle_key = ?", Integer.class,
                DashboardAuthService.accountKey(alice))).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_throttle WHERE throttle_key = ?", Integer.class,
                DashboardAuthService.clientAccountKey(alice, "10.77.0.2"))).isZero();
        assertThat(jdbc.queryForObject("SELECT sum(pending) FROM login_throttle WHERE throttle_key LIKE 'dashboard%'", Integer.class)).isZero();
    }

    @Test
    void anAccountAttackedFromVeryManyClientsIsLockedEverywhereButNotByAFewFailures() throws Exception {
        int ceiling = limiter.maxFailures() * DashboardAuthService.ACCOUNT_FAILURE_FACTOR;
        // One failure each from many addresses: no client or address reaches its own limit.
        for (int client = 1; client < ceiling; client++) {
            login("10.88." + (client / 200) + "." + (client % 200 + 1), alice, "wrong").andExpect(status().isUnauthorized());
        }
        login("10.88.9.9", alice, "wrong").andExpect(status().isTooManyRequests());

        // Everyone, Alice included, is limited on that account now; another account is not.
        login("10.77.0.5", alice, password).andExpect(status().isTooManyRequests());
        login("10.77.0.5", bob, password).andExpect(status().isOk());
    }

    @Test
    void theLockoutAnswersAlikeForRealAndMadeUpAccounts() throws Exception {
        String ghost = "ghost-" + suffix + "@throttle.test";
        for (int attempt = 0; attempt < limiter.maxFailures(); attempt++) {
            login(ADDRESS, alice, "wrong-" + attempt);
            login(ADDRESS, ghost, "wrong-" + attempt);
        }
        String real = login(ADDRESS, alice, password).andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        String made = login(ADDRESS, ghost, password).andExpect(status().isTooManyRequests()).andReturn().getResponse().getContentAsString();
        // Same status, title and wording: only the seconds left may differ.
        assertThat(real.replaceAll("\\d+", "#")).isEqualTo(made.replaceAll("\\d+", "#").replace("ghost-#@throttle.test", "alice-#@throttle.test"));
        assertThat(real).doesNotContain("Alice").doesNotContain(alice);
        // And an ordinary wrong password answers the same for both, too.
        login("10.77.0.9", bob, "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Wrong email or password"));
        login("10.77.0.9", "nobody-" + suffix + "@throttle.test", "wrong").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong email or password"));
    }

    @Test
    void manyFailuresAcrossDifferentAccountsStillLockTheAddress() throws Exception {
        int wide = limiter.maxFailures() * DashboardAuthService.ADDRESS_FAILURE_FACTOR;
        // Every attempt names a different e-mail, so no single account reaches its own limit.
        for (int attempt = 1; attempt < wide; attempt++) {
            login(ADDRESS, "stuff-" + attempt + "-" + suffix + "@throttle.test", "wrong").andExpect(status().isUnauthorized());
        }
        login(ADDRESS, "stuff-last-" + suffix + "@throttle.test", "wrong").andExpect(status().isTooManyRequests());

        // The address is now locked for everyone on it, valid accounts included; another address is not.
        login(ADDRESS, alice, password).andExpect(status().isTooManyRequests());
        login("10.77.0.3", alice, password).andExpect(status().isOk());
    }

    private ResultActions login(String address, String email, String pass) throws Exception {
        return mockMvc.perform(post("/api/v1/dashboard/auth/login").with(request -> {
            request.setRemoteAddr(address);
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\",\"password\":\"" + pass + "\"}"));
    }
}
