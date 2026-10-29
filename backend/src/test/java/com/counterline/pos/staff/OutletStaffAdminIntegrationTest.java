package com.counterline.pos.staff;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.AdminUser;
import com.counterline.pos.auth.AdminUserRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Head office manages outlets and staff over the real security chain, hashing and token signing.
 * Everything the test creates (outlets with a random code, their staff, an admin) is removed afterwards,
 * so the shared database is left as found.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OutletStaffAdminIntegrationTest {

    private static final String OUTLETS = "/api/v1/dashboard/admin/outlets";
    private static final String STAFF = "/api/v1/dashboard/admin/staff";
    private static final String DASHBOARD_OUTLETS = "/api/v1/dashboard/outlets";

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminEmail = "head-" + suffix + "@admin.test";
    private final String adminPassword = "pw-" + UUID.randomUUID();
    private final Set<String> usedPins = new HashSet<>();
    private final List<Long> outletIds = new ArrayList<>();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AdminUserRepository adminRepository;
    @Autowired
    PasswordEncoder passwordEncoder;

    private String admin;

    @BeforeEach
    void anAdmin() throws Exception {
        adminRepository.save(new AdminUser(adminEmail, "Head Office", passwordEncoder.encode(adminPassword), Instant.now()));
        admin = dashboardToken(adminEmail, adminPassword);
    }

    @AfterEach
    void removeWhatWasCreated() {
        for (Long outlet : outletIds) {
            jdbc.update("DELETE FROM register_session WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM till_device WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM staff WHERE outlet_id = ?", outlet);
            jdbc.update("DELETE FROM outlet WHERE id = ?", outlet);
        }
        jdbc.update("DELETE FROM staff WHERE email LIKE ?", "%-" + suffix + "@%");
        jdbc.update("DELETE FROM admin_user WHERE email = ?", adminEmail);
    }

    // ---- Outlets ----

    @Test
    void anOutletIsCreatedWithAnUpperCaseUniqueCodeAndListedWithDeactivatedOnes() throws Exception {
        String code = "t" + suffix.substring(0, 5);
        long id = createOutlet(code, "Outlet " + suffix, " 1 Test Street ");

        call(get(OUTLETS), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id==" + id + ")].code").value(hasItem(code.toUpperCase())))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].address").value(hasItem("1 Test Street")))
                .andExpect(jsonPath("$[?(@.id==" + id + ")].active").value(hasItem(true)));

        // The code is unique whatever its case; so is the name.
        call(post(OUTLETS), admin, json("code", code.toUpperCase(), "name", "Another " + suffix))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("code")));
        call(post(OUTLETS), admin, json("code", "N" + suffix.substring(0, 5), "name", ("Outlet " + suffix).toUpperCase()))
                .andExpect(status().isConflict());
        // Validation: a code and a name are required, and the code is a plain identifier.
        call(post(OUTLETS), admin, json("code", "", "name", "No Code")).andExpect(status().isBadRequest());
        call(post(OUTLETS), admin, json("code", "BAD CODE", "name", "Spaces " + suffix)).andExpect(status().isBadRequest());
        call(post(OUTLETS), admin, json("code", "TOOLONGCODE1", "name", "Long " + suffix)).andExpect(status().isBadRequest());
        call(post(OUTLETS), admin, json("code", "NN" + suffix.substring(0, 4), "name", "   ")).andExpect(status().isBadRequest());
    }

    @Test
    void anOutletsNameAndAddressChangeButItsCodeNever() throws Exception {
        String code = "e" + suffix.substring(0, 5);
        long id = createOutlet(code, "Before " + suffix, null);
        long other = createOutlet("f" + suffix.substring(0, 5), "Other " + suffix, null);

        // A code in the body is ignored: it is not part of the edit.
        call(put(OUTLETS + "/" + id), admin, json("code", "CHANGED", "name", "After " + suffix, "address", "2 New Road"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(code.toUpperCase()))
                .andExpect(jsonPath("$.name").value("After " + suffix))
                .andExpect(jsonPath("$.address").value("2 New Road"));
        assertThat(jdbc.queryForObject("SELECT code FROM outlet WHERE id = ?", String.class, id)).isEqualTo(code.toUpperCase());

        call(put(OUTLETS + "/" + id), admin, json("name", "Other " + suffix)).andExpect(status().isConflict());
        call(put(OUTLETS + "/" + id), admin, json("name", "")).andExpect(status().isBadRequest());
        call(put(OUTLETS + "/" + id), admin, json("name", "After " + suffix, "address", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        call(put(OUTLETS + "/987654321"), admin, json("name", "Nobody")).andExpect(status().isNotFound());
        assertThat(other).isNotEqualTo(id);
    }

    @Test
    void anOutletWithAnOpenRegisterCannotBeDeactivatedAndNothingIsEverDeleted() throws Exception {
        long outlet = createOutlet("d" + suffix.substring(0, 5), "Closing " + suffix, null);
        long manager = createStaff(outlet, "MANAGER", pin(), "Open Register Manager", null, null);
        jdbc.update("INSERT INTO register_session (outlet_id, opened_at, opened_by) VALUES (?, now(), ?)", outlet, manager);

        call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("open register")));
        assertThat(jdbc.queryForObject("SELECT active FROM outlet WHERE id = ?", Boolean.class, outlet)).isTrue();

        jdbc.update("UPDATE register_session SET closed_at = now(), closed_by = opened_by, version = version + 1 WHERE outlet_id = ?", outlet);
        call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // The history stays, the outlet leaves the public list, and it comes back on reactivation.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM register_session WHERE outlet_id = ?", Long.class, outlet)).isEqualTo(1L);
        mockMvc.perform(get("/api/v1/outlets")).andExpect(jsonPath("$[?(@.id==" + outlet + ")]").isEmpty());
        call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null).andExpect(status().isOk());
        call(post(OUTLETS + "/" + outlet + "/reactivate"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        mockMvc.perform(get("/api/v1/outlets")).andExpect(jsonPath("$[?(@.id==" + outlet + ")]").isNotEmpty());
        call(post(OUTLETS + "/987654321/deactivate"), admin, null).andExpect(status().isNotFound());
    }

    // ---- Staff ----

    @Test
    void cashiersAndManagersAreCreatedWithoutExposingCredentialsAndTheListKeepsDeactivatedStaff(CapturedOutput output) throws Exception {
        long outlet = createOutlet("s" + suffix.substring(0, 5), "Staffed " + suffix, null);
        String cashierPin = pin();
        String managerPin = pin();
        String managerEmail = "mgr-" + suffix + "@shop.test";
        String managerPassword = "pw-" + UUID.randomUUID();

        String cashierBody = call(post(STAFF), admin, json("fullName", "  Test Cashier ", "role", "STAFF", "outletId", outlet, "pin", cashierPin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("Test Cashier"))
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String managerBody = call(post(STAFF), admin, json("fullName", "Test Manager", "role", "MANAGER", "outletId", outlet,
                "pin", managerPin, "email", " " + managerEmail.toUpperCase() + " ", "password", managerPassword))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(managerEmail))
                .andReturn().getResponse().getContentAsString();

        for (String body : List.of(cashierBody, managerBody)) {
            assertThat(body).doesNotContain(cashierPin).doesNotContain(managerPin).doesNotContain(managerPassword)
                    .doesNotContainIgnoringCase("pinHmac").doesNotContainIgnoringCase("passwordHash").doesNotContain("$2");
        }
        // Stored hashed only.
        assertThat(jdbc.queryForObject("SELECT pin_hmac FROM staff WHERE id = ?", String.class, id(cashierBody)))
                .isNotEqualTo(cashierPin).hasSize(64);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM staff WHERE id = ?", String.class, id(managerBody)))
                .isNotEqualTo(managerPassword).startsWith("$2");
        assertThat(output.getAll()).doesNotContain(cashierPin).doesNotContain(managerPin).doesNotContain(managerPassword);

        // Deactivated staff stay on the list.
        call(post(STAFF + "/" + id(cashierBody) + "/deactivate"), admin, null).andExpect(status().isOk());
        call(get(STAFF + "?outletId=" + outlet), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.fullName=='Test Cashier')].active").value(hasItem(false)));
        call(get(STAFF), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.fullName=='Test Manager')].email").value(hasItem(managerEmail)));
    }

    @Test
    void staffInputIsValidatedAndPinsAndEmailsStayUniqueEvenWhenDeactivated() throws Exception {
        long outlet = createOutlet("v" + suffix.substring(0, 5), "Validated " + suffix, null);
        String pin = pin();
        String password = "pw-" + UUID.randomUUID();
        String email = "dup-" + suffix + "@shop.test";
        long first = createStaff(outlet, "MANAGER", pin, "First", email, password);

        // PIN and name.
        call(post(STAFF), admin, json("fullName", "Short Pin", "role", "STAFF", "outletId", outlet, "pin", "12345")).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "Letters", "role", "STAFF", "outletId", outlet, "pin", "12a456")).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "  ", "role", "STAFF", "outletId", outlet, "pin", pin())).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "No Role", "outletId", outlet, "pin", pin())).andExpect(status().isBadRequest());
        // Only a manager has a dashboard login, and e-mail and password come together.
        call(post(STAFF), admin, json("fullName", "Cashier Login", "role", "STAFF", "outletId", outlet, "pin", pin(),
                "email", "cashier-" + suffix + "@shop.test", "password", password)).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "Half Login", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", "half-" + suffix + "@shop.test")).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "Short Password", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", "short-" + suffix + "@shop.test", "password", "tooshort")).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "Long Password", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", "long-" + suffix + "@shop.test", "password", "p".repeat(73))).andExpect(status().isBadRequest());
        call(post(STAFF), admin, json("fullName", "Bad Email", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", "not-an-email", "password", password)).andExpect(status().isBadRequest());
        // Unknown outlet.
        call(post(STAFF), admin, json("fullName", "Nowhere", "role", "STAFF", "outletId", 987654321, "pin", pin())).andExpect(status().isNotFound());

        // Uniqueness, including a deactivated holder and the head-office namespace.
        call(post(STAFF + "/" + first + "/deactivate"), admin, null).andExpect(status().isOk());
        call(post(STAFF), admin, json("fullName", "Same Pin", "role", "STAFF", "outletId", outlet, "pin", pin()))
                .andExpect(status().isCreated());
        call(post(STAFF), admin, json("fullName", "Same Pin", "role", "STAFF", "outletId", outlet, "pin", pin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(not(containsString(pin))));
        call(post(STAFF), admin, json("fullName", "Same Email", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", email.toUpperCase(), "password", password)).andExpect(status().isConflict());
        call(post(STAFF), admin, json("fullName", "Admin Email", "role", "MANAGER", "outletId", outlet, "pin", pin(),
                "email", adminEmail, "password", password)).andExpect(status().isConflict());
    }

    @Test
    void staffAreEditedMovedAndDemotedAndADemotedManagerLosesTheDashboardLogin() throws Exception {
        long outlet = createOutlet("m" + suffix.substring(0, 5), "From " + suffix, null);
        long elsewhere = createOutlet("n" + suffix.substring(0, 5), "To " + suffix, null);
        long closed = createOutlet("c" + suffix.substring(0, 5), "Closed " + suffix, null);
        call(post(OUTLETS + "/" + closed + "/deactivate"), admin, null).andExpect(status().isOk());
        String email = "edit-" + suffix + "@shop.test";
        String password = "pw-" + UUID.randomUUID();
        long manager = createStaff(outlet, "MANAGER", pin(), "Edit Me", email, password);
        dashboardToken(email, password);

        call(put(STAFF + "/" + manager), admin, json("fullName", "Edited Name", "role", "MANAGER", "outletId", elsewhere))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Edited Name"))
                .andExpect(jsonPath("$.outletId").value(elsewhere))
                .andExpect(jsonPath("$.email").value(email));
        // Not to a deactivated outlet, not with a blank name, not with a login on a cashier.
        call(put(STAFF + "/" + manager), admin, json("fullName", "Edited Name", "role", "MANAGER", "outletId", closed)).andExpect(status().isNotFound());
        call(put(STAFF + "/" + manager), admin, json("fullName", " ", "role", "MANAGER", "outletId", elsewhere)).andExpect(status().isBadRequest());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Cashier", "role", "STAFF", "outletId", elsewhere,
                "email", email, "password", password)).andExpect(status().isBadRequest());
        call(put(STAFF + "/987654321"), admin, json("fullName", "Nobody", "role", "STAFF", "outletId", elsewhere)).andExpect(status().isNotFound());

        // A new password replaces the login.
        String newPassword = "pw-" + UUID.randomUUID();
        call(put(STAFF + "/" + manager), admin, json("fullName", "Edited Name", "role", "MANAGER", "outletId", elsewhere,
                "email", email, "password", newPassword)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password))).andExpect(status().isUnauthorized());
        dashboardToken(email, newPassword);

        // Demoted: a cashier has no dashboard login.
        call(put(STAFF + "/" + manager), admin, json("fullName", "Edited Name", "role", "STAFF", "outletId", elsewhere))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STAFF"))
                .andExpect(jsonPath("$.email").doesNotExist());
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", newPassword))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT password_hash IS NULL AND email IS NULL FROM staff WHERE id = ?", Boolean.class, manager)).isTrue();
    }

    @Test
    void aDeactivatedStaffMemberCannotSignInAtTheTillOrTheDashboardUntilReactivated() throws Exception {
        long outlet = createOutlet("l" + suffix.substring(0, 5), "Login " + suffix, null);
        String managerPin = pin();
        String cashierPin = pin();
        String email = "lock-" + suffix + "@shop.test";
        String password = "pw-" + UUID.randomUUID();
        long manager = createStaff(outlet, "MANAGER", managerPin, "Lock Manager", email, password);
        long cashier = createStaff(outlet, "STAFF", cashierPin, "Lock Cashier", null, null);
        String deviceKey = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", managerPin, "outletId", outlet)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceKey");
        String cashierToken = tillLogin(cashierPin, deviceKey).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(cashierToken, "$.token");
        dashboardToken(email, password);

        call(post(STAFF + "/" + cashier + "/deactivate"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        call(post(STAFF + "/" + manager + "/deactivate"), admin, null).andExpect(status().isOk());
        tillLogin(cashierPin, deviceKey).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password))).andExpect(status().isUnauthorized());
        // A token issued earlier no longer identifies them either.
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andExpect(status().isUnauthorized());

        call(post(STAFF + "/" + cashier + "/reactivate"), admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        call(post(STAFF + "/" + manager + "/reactivate"), admin, null).andExpect(status().isOk());
        tillLogin(cashierPin, deviceKey).andExpect(status().isOk());
        dashboardToken(email, password);

        // Not back into an outlet that is closed.
        call(post(STAFF + "/" + cashier + "/deactivate"), admin, null).andExpect(status().isOk());
        jdbc.update("UPDATE outlet SET active = false WHERE id = ?", outlet);
        call(post(STAFF + "/" + cashier + "/reactivate"), admin, null).andExpect(status().isConflict());
        call(post(STAFF + "/987654321/deactivate"), admin, null).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff WHERE id IN (?, ?)", Long.class, manager, cashier)).isEqualTo(2L);
    }

    @Test
    void aPinIsResetWithoutBeingReturnedOrLoggedAndStaysUnique(CapturedOutput output) throws Exception {
        long outlet = createOutlet("p" + suffix.substring(0, 5), "Pins " + suffix, null);
        String managerPin = pin();
        String oldPin = pin();
        String newPin = pin();
        String heldPin = pin();
        createStaff(outlet, "MANAGER", managerPin, "Pin Manager", null, null);
        long cashier = createStaff(outlet, "STAFF", oldPin, "Pin Cashier", null, null);
        long holder = createStaff(outlet, "STAFF", heldPin, "Pin Holder", null, null);
        call(post(STAFF + "/" + holder + "/deactivate"), admin, null).andExpect(status().isOk());
        String deviceKey = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", managerPin, "outletId", outlet)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceKey");

        String body = call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", newPin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(newPin).doesNotContain(oldPin).doesNotContainIgnoringCase("pinHmac");
        assertThat(output.getAll()).doesNotContain(newPin).doesNotContain(oldPin);
        tillLogin(oldPin, deviceKey).andExpect(status().isUnauthorized());
        tillLogin(newPin, deviceKey).andExpect(status().isOk());

        call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", heldPin)).andExpect(status().isConflict());
        call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", newPin)).andExpect(status().isOk());
        call(post(STAFF + "/" + cashier + "/pin"), admin, json("pin", "12345")).andExpect(status().isBadRequest());
        call(post(STAFF + "/987654321/pin"), admin, json("pin", pin())).andExpect(status().isNotFound());
    }

    // ---- Dashboard logins of existing managers ----

    @Test
    void headOfficeAddsChangesAndRemovesAnExistingManagersDashboardLogin() throws Exception {
        long outlet = createOutlet("g" + suffix.substring(0, 5), "Logins " + suffix, null);
        long manager = createStaff(outlet, "MANAGER", pin(), "Login Manager", null, null);
        long other = createStaff(outlet, "MANAGER", pin(), "Other Manager", "other-" + suffix + "@shop.test", "pw-" + UUID.randomUUID());
        String email = "added-" + suffix + "@shop.test";
        String password = "pw-" + UUID.randomUUID();
        String edit = "\"fullName\":\"Login Manager\",\"role\":\"MANAGER\",\"outletId\":" + outlet;

        // Adding a login needs both an e-mail and a password, and a valid one.
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "email", email))
                .andExpect(status().isBadRequest());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "password", password))
                .andExpect(status().isBadRequest());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "email", email, "password", "short"))
                .andExpect(status().isBadRequest());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "email", "other-" + suffix + "@shop.test", "password", password))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT email IS NULL AND password_hash IS NULL FROM staff WHERE id = ?", Boolean.class, manager)).isTrue();

        String added = call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "email", email.toUpperCase(), "password", password))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email))
                .andReturn().getResponse().getContentAsString();
        assertThat(added).doesNotContain(password).doesNotContainIgnoringCase("passwordHash").doesNotContain("$2");
        dashboardToken(email, password);

        // Only the e-mail changes: the password is kept.
        String changed = "changed-" + suffix + "@shop.test";
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "email", changed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(changed));
        dashboardToken(changed, password);
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email, "password", password))).andExpect(status().isUnauthorized());

        // Only the password changes (the e-mail stays); a blank or too weak one never replaces it.
        String reset = "pw-" + UUID.randomUUID();
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "password", reset))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(changed));
        dashboardToken(changed, reset);
        call(put(STAFF + "/" + manager), admin, "{" + edit + ",\"email\":\"\",\"password\":\"\"}").andExpect(status().isOk());
        call(put(STAFF + "/" + manager), admin, "{" + edit + ",\"password\":\"            \"}").andExpect(status().isOk());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet)).andExpect(status().isOk());
        call(put(STAFF + "/" + manager), admin, json("fullName", "Login Manager", "role", "MANAGER", "outletId", outlet, "password", "tooshort"))
                .andExpect(status().isBadRequest());
        dashboardToken(changed, reset);
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", changed, "password", password))).andExpect(status().isUnauthorized());

        // Removal drops both, keeps the person, and is idempotent.
        call(delete(STAFF + "/" + manager + "/dashboard-login"), admin, null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").doesNotExist()).andExpect(jsonPath("$.active").value(true));
        call(delete(STAFF + "/" + manager + "/dashboard-login"), admin, null).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT email IS NULL AND password_hash IS NULL FROM staff WHERE id = ?", Boolean.class, manager)).isTrue();
        mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", changed, "password", reset))).andExpect(status().isUnauthorized());
        call(delete(STAFF + "/987654321/dashboard-login"), admin, null).andExpect(status().isNotFound());
        assertThat(other).isNotEqualTo(manager);
    }

    // ---- A manager's dashboard session ends as soon as the account or outlet changes (till and approval tokens: StaffSessionRevocationIntegrationTest) ----

    @Test
    void aManagersDashboardTokenStopsWorkingWhenTheyAreDeactivatedDemotedMovedOrLoseTheirLogin() throws Exception {
        long outlet = createOutlet("r" + suffix.substring(0, 5), "Revoke " + suffix, null);
        long elsewhere = createOutlet("q" + suffix.substring(0, 5), "Elsewhere " + suffix, null);
        String password = "pw-" + UUID.randomUUID();
        for (String state : List.of("deactivate", "demote", "move", "remove-login")) {
            String email = state + "-" + suffix + "@shop.test";
            long manager = createStaff(outlet, "MANAGER", pin(), "Revoked " + state, email, password);
            String token = dashboardToken(email, password);
            String bystanderEmail = "by-" + state + "-" + suffix + "@shop.test";
            createStaff(outlet, "MANAGER", pin(), "Bystander " + state, bystanderEmail, password);
            String bystander = dashboardToken(bystanderEmail, password);
            call(get(DASHBOARD_OUTLETS), token, null).andExpect(status().isOk());

            switch (state) {
                case "deactivate" -> call(post(STAFF + "/" + manager + "/deactivate"), admin, null).andExpect(status().isOk());
                case "demote" -> call(put(STAFF + "/" + manager), admin, json("fullName", "Revoked " + state, "role", "STAFF", "outletId", outlet)).andExpect(status().isOk());
                case "move" -> call(put(STAFF + "/" + manager), admin, json("fullName", "Revoked " + state, "role", "MANAGER", "outletId", elsewhere)).andExpect(status().isOk());
                default -> call(delete(STAFF + "/" + manager + "/dashboard-login"), admin, null).andExpect(status().isOk());
            }

            // The token issued before the change is refused on every dashboard request, saying nothing about the account.
            for (String path : List.of(DASHBOARD_OUTLETS, "/api/v1/dashboard/live", "/api/v1/dashboard/devices", "/api/v1/dashboard/pin-locks")) {
                String body = call(get(path), token, null).andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
                assertThat(body).doesNotContain(email).doesNotContain("Revoked").contains("Log in to continue.");
            }
            call(get(STAFF), token, null).andExpect(status().isUnauthorized());
            // Head office and other managers are unaffected.
            call(get(DASHBOARD_OUTLETS), admin, null).andExpect(status().isOk());
            call(get(DASHBOARD_OUTLETS), bystander, null).andExpect(status().isOk());

            // Putting everything back (reactivating, promoting, moving back, recreating the very same
            // login) never revives the old token; only a new login works.
            String restore = "\"fullName\":\"Revoked " + state + "\",\"role\":\"MANAGER\",\"outletId\":" + outlet;
            String login = ",\"email\":\"" + email + "\",\"password\":\"" + password + "\"";
            switch (state) {
                case "deactivate" -> call(post(STAFF + "/" + manager + "/reactivate"), admin, null).andExpect(status().isOk());
                case "demote" -> call(put(STAFF + "/" + manager), admin, "{" + restore + login + "}").andExpect(status().isOk());
                case "move" -> call(put(STAFF + "/" + manager), admin, "{" + restore + "}").andExpect(status().isOk());
                default -> call(put(STAFF + "/" + manager), admin, "{" + restore + login + "}").andExpect(status().isOk());
            }
            call(get(DASHBOARD_OUTLETS), token, null).andExpect(status().isUnauthorized());
            call(get(DASHBOARD_OUTLETS), dashboardToken(email, password), null).andExpect(status().isOk());
        }
    }

    @Test
    void aManagersDashboardTokenStopsWorkingWhenTheirOutletIsDeactivated() throws Exception {
        long outlet = createOutlet("z" + suffix.substring(0, 5), "Shut " + suffix, null);
        long open = createOutlet("y" + suffix.substring(0, 5), "Staying open " + suffix, null);
        String password = "pw-" + UUID.randomUUID();
        createStaff(outlet, "MANAGER", pin(), "Shut Manager", "shut-" + suffix + "@shop.test", password);
        createStaff(open, "MANAGER", pin(), "Open Manager", "open-" + suffix + "@shop.test", password);
        String shut = dashboardToken("shut-" + suffix + "@shop.test", password);
        String stillOpen = dashboardToken("open-" + suffix + "@shop.test", password);
        call(get(DASHBOARD_OUTLETS), shut, null).andExpect(status().isOk());

        call(post(OUTLETS + "/" + outlet + "/deactivate"), admin, null).andExpect(status().isOk());

        call(get(DASHBOARD_OUTLETS), shut, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(not(containsString("Shut"))));
        call(get(DASHBOARD_OUTLETS), stillOpen, null).andExpect(status().isOk());
        call(get(DASHBOARD_OUTLETS), admin, null).andExpect(status().isOk());

        // Reopening the outlet does not bring the old token back; a new login works.
        call(post(OUTLETS + "/" + outlet + "/reactivate"), admin, null).andExpect(status().isOk());
        call(get(DASHBOARD_OUTLETS), shut, null).andExpect(status().isUnauthorized());
        call(get(DASHBOARD_OUTLETS), dashboardToken("shut-" + suffix + "@shop.test", password), null).andExpect(status().isOk());
        call(get(DASHBOARD_OUTLETS), stillOpen, null).andExpect(status().isOk());
    }

    @Test
    void aStaffTokenWithoutTheAuthSessionVersionOrWithAnotherOneIsNeverAccepted() throws Exception {
        long outlet = createOutlet("w" + suffix.substring(0, 5), "Versions " + suffix, null);
        String password = "pw-" + UUID.randomUUID();
        String email = "ver-" + suffix + "@shop.test";
        long manager = createStaff(outlet, "MANAGER", pin(), "Versioned Manager", email, password);
        call(get(DASHBOARD_OUTLETS), dashboardToken(email, password), null).andExpect(status().isOk());
        int current = jdbc.queryForObject("SELECT auth_session_version FROM staff WHERE id = ?", Integer.class, manager);

        // The same token, but without the version, or with a different one.
        call(get(DASHBOARD_OUTLETS), signed(manager, outlet, null), null).andExpect(status().isUnauthorized());
        call(get(DASHBOARD_OUTLETS), signed(manager, outlet, current + 1), null).andExpect(status().isUnauthorized());
        call(get(DASHBOARD_OUTLETS), signed(manager, outlet, current), null).andExpect(status().isOk());
        // The version is never shown in a response.
        assertThat(call(get(STAFF + "?outletId=" + outlet), admin, null).andReturn().getResponse().getContentAsString())
                .doesNotContainIgnoringCase("authSessionVersion");
    }

    // ---- Authorization ----

    @Test
    void onlyHeadOfficeMayManageOutletsAndStaff() throws Exception {
        long outlet = createOutlet("a" + suffix.substring(0, 5), "Authz " + suffix, null);
        String managerPin = pin();
        String email = "authz-" + suffix + "@shop.test";
        String password = "pw-" + UUID.randomUUID();
        long manager = createStaff(outlet, "MANAGER", managerPin, "Authz Manager", email, password);
        String managerToken = dashboardToken(email, password);
        String deviceKey = JsonPath.read(mockMvc.perform(post("/api/v1/auth/terminal-setup").contentType(MediaType.APPLICATION_JSON)
                        .content(json("pin", managerPin, "outletId", outlet)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceKey");
        String tillToken = JsonPath.read(tillLogin(managerPin, deviceKey).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.token");

        List<MockHttpServletRequestBuilder> requests = List.of(
                get(OUTLETS), post(OUTLETS), put(OUTLETS + "/" + outlet), post(OUTLETS + "/" + outlet + "/deactivate"),
                post(OUTLETS + "/" + outlet + "/reactivate"), get(STAFF), post(STAFF), put(STAFF + "/" + manager),
                post(STAFF + "/" + manager + "/deactivate"), post(STAFF + "/" + manager + "/reactivate"),
                post(STAFF + "/" + manager + "/pin"));
        for (MockHttpServletRequestBuilder request : requests) {
            // The same builders, one caller at a time: none is ever reached.
            call(request, managerToken, "{}").andExpect(status().isForbidden());
        }
        for (String path : List.of(OUTLETS, STAFF)) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + tillToken)).andExpect(status().isForbidden());
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + managerToken)).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, manager)).isTrue();
    }

    // ---- Helpers ----

    @Autowired
    JwtEncoder jwtEncoder;

    /** A correctly signed manager dashboard token, as the login issues, with the given session version (none if null). */
    private String signed(long manager, long outlet, Integer version) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("counterline-pos").id(UUID.randomUUID().toString())
                .subject(String.valueOf(manager)).issuedAt(now).expiresAt(now.plusSeconds(600))
                .claim("role", "MANAGER").claim("outlet", outlet).claim("channel", "dashboard");
        if (version != null) {
            claims.claim("sver", version);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    private long createOutlet(String code, String name, String address) throws Exception {
        String body = call(post(OUTLETS), admin, json("code", code, "name", name, "address", address))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.id")).longValue();
        outletIds.add(id);
        return id;
    }

    private long createStaff(long outlet, String role, String pin, String name, String email, String password) throws Exception {
        return id(call(post(STAFF), admin, json("fullName", name, "role", role, "outletId", outlet, "pin", pin,
                "email", email, "password", password)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private static long id(String body) {
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** A PIN no other test fixture uses and this test has not used yet. */
    private String pin() {
        String pin;
        do {
            pin = String.valueOf(ThreadLocalRandom.current().nextInt(700000, 800000));
        } while (!usedPins.add(pin));
        return pin;
    }

    private ResultActions tillLogin(String pin, String deviceKey) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json("pin", pin, "deviceKey", deviceKey)));
    }

    private String dashboardToken(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/dashboard/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
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
