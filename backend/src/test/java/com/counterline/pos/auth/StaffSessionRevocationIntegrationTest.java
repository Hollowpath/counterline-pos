package com.counterline.pos.auth;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A staff token (till, manager approval or manager dashboard) is only good for the account state it
 * was issued under. After deactivation, a PIN reset, a role or outlet change, a login change or the
 * outlet's closing, the old token is refused on ordinary protected endpoints with a generic 401, and
 * restoring the account, role, outlet or PIN never brings it back; only a new sign-in works.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class StaffSessionRevocationIntegrationTest {

    private static final String OUTLETS = "/api/v1/dashboard/admin/outlets";
    private static final String STAFF = "/api/v1/dashboard/admin/staff";

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminEmail = "head-" + suffix + "@admin.test";
    private final String adminPassword = "pw-" + UUID.randomUUID();
    private final String password = "pw-" + UUID.randomUUID();
    private final Set<String> usedPins = new HashSet<>();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    PasswordEncoder passwordEncoder;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    ManagerApproval managerApproval;

    private String admin;
    private long outlet;
    private long elsewhere;
    private String deviceKey;

    @BeforeEach
    void anOutletWithATillAndHeadOffice() throws Exception {
        adminRepository.save(new AdminUser(adminEmail, "Head Office", passwordEncoder.encode(adminPassword), Instant.now()));
        admin = dashboardToken(adminEmail, adminPassword);
        outlet = createOutlet("u" + suffix.substring(0, 5));
        elsewhere = createOutlet("v" + suffix.substring(0, 5));
        String setupPin = pin();
        createStaff(outlet, "MANAGER", setupPin, "Set-up Manager", null);
        // An open outlet keeps a manager, so a manager moved there and back is never its only one.
        createStaff(elsewhere, "MANAGER", pin(), "Elsewhere Manager", null);
        deviceKey = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", setupPin, "outletId", outlet)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceKey");
    }

    @AfterEach
    void removeWhatWasCreated() {
        for (long id : new long[] {outlet, elsewhere}) {
            jdbc.update("DELETE FROM till_device WHERE outlet_id = ?", id);
            jdbc.update("DELETE FROM staff WHERE outlet_id = ?", id);
            jdbc.update("DELETE FROM outlet WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM admin_user WHERE email = ?", adminEmail);
    }

    private record Tokens(String till, String approval, String discount, String dashboard) {
    }

    /** What each kind of change does to a token issued before it, and that undoing it does not help. */
    @Test
    void aCashiersTillTokenIsRefusedOnBusinessEndpointsAfterEveryAuthenticationChange() throws Exception {
        for (String change : List.of("deactivate", "pin-reset", "role", "move", "outlet-closed")) {
            String pin = pin();
            long cashier = createStaff(outlet, "STAFF", pin, "Cashier " + change, null);
            String token = tillLogin(pin);
            expectWorking(token);

            switch (change) {
                case "deactivate" -> call(post(STAFF + "/" + cashier + "/deactivate"), admin, null).andExpect(status().isOk());
                case "pin-reset" -> call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", pin())).andExpect(status().isOk());
                case "role" -> edit(cashier, "Cashier " + change, "MANAGER", outlet, null).andExpect(status().isOk());
                case "move" -> edit(cashier, "Cashier " + change, "STAFF", elsewhere, null).andExpect(status().isOk());
                default -> call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null).andExpect(status().isOk());
            }
            expectRefused(token, "Cashier " + change);

            // Put everything back: the old token still does not work; a new sign-in does.
            switch (change) {
                case "deactivate" -> call(post(STAFF + "/" + cashier + "/reactivate"), admin, null).andExpect(status().isOk());
                case "pin-reset" -> call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", pin)).andExpect(status().isOk());
                case "role" -> edit(cashier, "Cashier " + change, "STAFF", outlet, null).andExpect(status().isOk());
                case "move" -> edit(cashier, "Cashier " + change, "STAFF", outlet, null).andExpect(status().isOk());
                default -> call(post(OUTLETS + "/" + outlet + "/reactivate"), admin, null).andExpect(status().isOk());
            }
            expectRefused(token, "Cashier " + change);
            expectWorking(tillLogin(pin));
        }
    }

    @Test
    void aManagersTillApprovalAndDashboardTokensAllStopAndStayStopped() throws Exception {
        for (String change : List.of("deactivate", "pin-reset", "demote", "move", "login-removed", "outlet-closed")) {
            String pin = pin();
            String email = change + "-" + suffix + "@shop.test";
            long manager = createStaff(outlet, "MANAGER", pin, "Manager " + change, email);
            Tokens before = tokens(pin, email);
            expectWorking(before.till());
            expectApprovalAccepted(before.approval());
            call(get("/api/v1/dashboard/outlets"), before.dashboard(), null).andExpect(status().isOk());
            Staff cashier = staffRepository.findById(createStaff(outlet, "STAFF", pin(), "Counter " + change, null)).orElseThrow();

            switch (change) {
                case "deactivate" -> call(post(STAFF + "/" + manager + "/deactivate"), admin, null).andExpect(status().isOk());
                case "pin-reset" -> call(post(STAFF + "/" + manager + "/pin"), admin, json("pin", pin())).andExpect(status().isOk());
                case "demote" -> edit(manager, "Manager " + change, "STAFF", outlet, null).andExpect(status().isOk());
                case "move" -> edit(manager, "Manager " + change, "MANAGER", elsewhere, null).andExpect(status().isOk());
                case "login-removed" -> call(delete(STAFF + "/" + manager + "/dashboard-login"), admin, null).andExpect(status().isOk());
                default -> call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null).andExpect(status().isOk());
            }
            expectAllRefused(before, "Manager " + change, email);
            expectDiscountRefused(cashier, before.discount(), "Manager " + change, email);

            // Restore everything, recreating the very same login and PIN.
            switch (change) {
                case "deactivate" -> call(post(STAFF + "/" + manager + "/reactivate"), admin, null).andExpect(status().isOk());
                case "pin-reset" -> call(post(STAFF + "/" + manager + "/pin"), admin, json("pin", pin)).andExpect(status().isOk());
                case "demote", "login-removed" -> edit(manager, "Manager " + change, "MANAGER", outlet, email).andExpect(status().isOk());
                case "move" -> edit(manager, "Manager " + change, "MANAGER", outlet, null).andExpect(status().isOk());
                default -> call(post(OUTLETS + "/" + outlet + "/reactivate"), admin, null).andExpect(status().isOk());
            }
            expectAllRefused(before, "Manager " + change, email);
            expectDiscountRefused(cashier, before.discount(), "Manager " + change, email);
            Tokens after = tokens(pin, email);
            expectWorking(after.till());
            expectApprovalAccepted(after.approval());
            assertThat(managerApproval.requireManager(cashier, after.discount()).getId()).isEqualTo(manager);
            call(get("/api/v1/dashboard/outlets"), after.dashboard(), null).andExpect(status().isOk());
        }
    }

    @Test
    void closingAnOutletEndsTheSessionsOfItsCashiersToo() throws Exception {
        String cashierPin = pin();
        createStaff(outlet, "STAFF", cashierPin, "Closing Cashier", null);
        String token = tillLogin(cashierPin);
        long otherOutletCashier = createStaff(elsewhere, "STAFF", pin(), "Other Outlet Cashier", null);
        assertThat(otherOutletCashier).isPositive();
        expectWorking(token);

        call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null).andExpect(status().isOk());
        expectRefused(token, "Closing Cashier");
        call(post(OUTLETS + "/" + outlet + "/reactivate"), admin, null).andExpect(status().isOk());
        expectRefused(token, "Closing Cashier");
        expectWorking(tillLogin(cashierPin));
    }

    // ---- Helpers ----

    /** A protected business endpoint that a valid till token reaches (it is not the 401 of a refused one). */
    private void expectWorking(String token) throws Exception {
        mockMvc.perform(get("/api/v1/register").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isOk());
    }

    private void expectApprovalAccepted(String token) throws Exception {
        // A void of an order that does not exist: past authentication the answer is not 401.
        int status = mockMvc.perform(post("/api/v1/orders/987654321/void").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    private void expectRefused(String tillToken, String name) throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/register"), get("/api/v1/auth/me"),
                post("/api/v1/orders/987654321/void").contentType(MediaType.APPLICATION_JSON).content("{}"))) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + tillToken);
            mockMvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value(not(containsString(name))))
                    .andExpect(jsonPath("$.detail").value("Log in to continue."));
        }
    }

    private void expectAllRefused(Tokens tokens, String name, String email) throws Exception {
        expectRefused(tokens.till(), name);
        String body = mockMvc.perform(post("/api/v1/orders/987654321/void").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.approval()))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(name).doesNotContain(email).doesNotContain("sver");
        String dashboard = call(get("/api/v1/dashboard/outlets"), tokens.dashboard(), null)
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(dashboard).doesNotContain(name).doesNotContain(email).doesNotContain("sver");
    }

    /** A body-carried discount approval is refused like a bearer token, and says no more than that it is needed. */
    private void expectDiscountRefused(Staff cashier, String discountToken, String name, String email) {
        assertThatThrownBy(() -> managerApproval.requireManager(cashier, discountToken))
                .isInstanceOf(SpecialAccessDeniedException.class)
                .hasMessage("Manager approval needed")
                .satisfies(refused -> assertThat(refused.getMessage()).doesNotContain(name).doesNotContain(email));
    }

    private Tokens tokens(String pin, String email) throws Exception {
        String till = tillLogin(pin);
        return new Tokens(till, approval(till, pin, "VOID"), approval(till, pin, "DISCOUNT"), dashboardToken(email, password));
    }

    private String approval(String till, String pin, String operation) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/auth/special-access").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", pin, "operation", operation)).header(HttpHeaders.AUTHORIZATION, "Bearer " + till))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private long createOutlet(String code) throws Exception {
        String body = call(post(OUTLETS), admin, json("code", code, "name", "Revocation " + code + suffix))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long createStaff(long outletId, String role, String pin, String name, String email) throws Exception {
        String body = call(post(STAFF), admin, json("fullName", name, "role", role, "outletId", outletId, "pin", pin,
                "email", email, "password", email == null ? null : password))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** A staff edit; with an e-mail it also (re)creates that dashboard login with the test password. */
    private ResultActions edit(long id, String name, String role, long outletId, String email) throws Exception {
        return call(put(STAFF + "/" + id), admin, json("fullName", name, "role", role, "outletId", outletId,
                "email", email, "password", email == null ? null : password));
    }

    private String pin() {
        String pin;
        do {
            pin = String.valueOf(ThreadLocalRandom.current().nextInt(700000, 800000));
        } while (!usedPins.add(pin));
        return pin;
    }

    private String tillLogin(String pin) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", pin, "deviceKey", deviceKey)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private String dashboardToken(String email, String password) throws Exception {
        return JsonPath.read(mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    /** A JSON object from name/value pairs; null values are left out, numbers are bare, the rest are strings. */
    private static String json(Object... pairs) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value == null) {
                continue;
            }
            if (out.length() > 1) {
                out.append(',');
            }
            out.append('"').append(pairs[i]).append("\":");
            out.append(value instanceof Number ? value.toString()
                    : '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"');
        }
        return out.append('}').toString();
    }
}
