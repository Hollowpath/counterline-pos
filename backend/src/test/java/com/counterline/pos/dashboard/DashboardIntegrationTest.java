package com.counterline.pos.dashboard;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.AdminUser;
import com.counterline.pos.auth.AdminUserRepository;
import com.counterline.pos.auth.PinHasher;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The back-office dashboard with real tokens: head office (email and password) sees every outlet
 * and edits the catalogue; a manager (PIN) sees their own outlet. Fixture: catalog-fixture.sql
 * (item 1 Pouch Cheese 16.00 in folder 1 Pouch, item 4 Tin Caramel 29.00).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql("/catalog-fixture.sql")
class DashboardIntegrationTest {

    private static final String TODAY = LocalDate.now(ZoneId.of("Asia/Kuala_Lumpur")).toString();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    AdminUserRepository adminRepository;

    @Autowired
    PinHasher pinHasher;

    @Autowired
    PasswordEncoder passwordEncoder;

    private long second;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer, admin_user RESTART IDENTITY CASCADE");
        jdbc.update("INSERT INTO outlet (code, name) VALUES ('O2', 'Outlet 2') ON CONFLICT (code) DO NOTHING");
        second = jdbc.queryForObject("SELECT id FROM outlet WHERE code = 'O2'", Long.class);
        Long cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L));
        Staff secondManager = new Staff("Second Manager", pinHasher.hash("888888"), StaffRole.MANAGER, second);
        secondManager.setDashboardLogin("second.manager@counterline.test", passwordEncoder.encode("manager pass"));
        staffRepository.save(secondManager);
        adminRepository.save(new AdminUser("admin@counterline.test", "Head Office", passwordEncoder.encode("correct horse"),
                Instant.now()));
        TestRegister.open(jdbc, cashierId);
    }

    @Test
    void headOfficeLogsInWithEmailAndPasswordAndSeesEveryOutlet() throws Exception {
        login("/api/v1/dashboard/auth/login", "{\"email\":\"admin@counterline.test\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong email or password"));
        login("/api/v1/dashboard/auth/login", "{\"email\":\"nobody@counterline.test\",\"password\":\"correct horse\"}")
                .andExpect(status().isUnauthorized());

        String admin = token(login("/api/v1/dashboard/auth/login",
                "{\"email\":\" Admin@Counterline.test \",\"password\":\"correct horse\"}")
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.name").value("Head Office"))
                .andExpect(jsonPath("$.outlet").doesNotExist()));

        sellAtOutlet1();
        call(get("/api/v1/dashboard/live"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.outlets[*].name").value(contains("Outlet 1", "Outlet 2")))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.netSales").value(16.0))
                .andExpect(jsonPath("$.outlets[0].registerOpen").value(true))
                .andExpect(jsonPath("$.outlets[0].recentOrders[0].items").value("1× Pouch Cheese"))
                .andExpect(jsonPath("$.outlets[0].recentOrders[0].staffName").value("Test Cashier"))
                .andExpect(jsonPath("$.outlets[1].orderCount").value(0));
        call(get("/api/v1/dashboard/outlets/" + second + "/reports/sales-summary").param("from", TODAY).param("to", TODAY),
                admin, null).andExpect(status().isOk()).andExpect(jsonPath("$.orderCount").value(0));
        // Customers moved to the dashboard.
        call(get("/api/v1/customers"), admin, null).andExpect(status().isOk());
        // Head office has no till: the POS API that needs one turns it away.
        call(get("/api/v1/register"), admin, null).andExpect(status().isUnauthorized());
    }

    @Test
    void aManagerLogsInWithEmailAndPasswordAndSeesOnlyTheirOutlet() throws Exception {
        login("/api/v1/dashboard/auth/login", "{\"email\":\"second.manager@counterline.test\",\"password\":\"wrong\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Wrong email or password"));
        // The PIN no longer opens the dashboard.
        login("/api/v1/dashboard/auth/pin-login", "{\"pin\":\"888888\"}").andExpect(status().isUnauthorized());
        String manager = managerToken();

        call(get("/api/v1/dashboard/live"), manager, null)
                .andExpect(jsonPath("$.outlets[*].name").value(contains("Outlet 2")));
        call(get("/api/v1/dashboard/outlets/1/reports/sales-summary").param("from", TODAY).param("to", TODAY), manager, null)
                .andExpect(status().isNotFound());
        call(get("/api/v1/dashboard/outlets/" + second + "/reports/hourly-sales").param("from", TODAY).param("to", TODAY),
                manager, null).andExpect(status().isOk());
        call(get("/api/v1/dashboard/outlets/" + second + "/reports/nonsense").param("from", TODAY).param("to", TODAY),
                manager, null).andExpect(status().isNotFound());
        // The catalogue is head office's.
        call(get("/api/v1/dashboard/catalog/items"), manager, null).andExpect(status().isForbidden());
        call(get("/api/v1/dashboard/discounts"), manager, null).andExpect(status().isForbidden());
    }

    @Test
    void headOfficeChangesPricesForEveryOutletWithoutRewritingPastSales() throws Exception {
        String admin = adminToken();
        String pastOrder = sellAtOutlet1();

        call(get("/api/v1/dashboard/catalog/items"), admin, null)
                .andExpect(jsonPath("$[?(@.name == 'Pouch')].items[*].name").value(hasItem("Pouch Retired")));
        call(put("/api/v1/dashboard/catalog/items/1"), admin, "{\"name\":\"Pouch Cheese\",\"price\":17.50,\"active\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.price").value(17.5));
        call(put("/api/v1/dashboard/catalog/items/1"), admin, "{\"name\":\"Pouch Caramel\",\"price\":17.50}")
                .andExpect(status().isConflict());

        // The till sells at the new price; the earlier sale keeps its own.
        mockMvc.perform(get("/api/v1/catalog/categories/1/items").with(cashier()))
                .andExpect(jsonPath("$.items[?(@.name == 'Pouch Cheese')].price").value(contains(17.5)));
        mockMvc.perform(get("/api/v1/orders/" + pastOrder).with(cashier()))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(16.0));

        String created = call(post("/api/v1/dashboard/catalog/items"), admin,
                "{\"categoryId\":1,\"name\":\"Pouch Matcha\",\"price\":20.00}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Integer newItem = JsonPath.read(created, "$.id");
        call(put("/api/v1/dashboard/catalog/items/" + newItem), admin, "{\"name\":\"Pouch Matcha\",\"price\":20.00,\"active\":false}")
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/v1/catalog/categories/1/items").with(cashier()))
                .andExpect(jsonPath("$.items[*].name").value(not(hasItem("Pouch Matcha"))));
    }

    @Test
    void headOfficeEditsTheDiscountsOfferedAtTheTills() throws Exception {
        String admin = adminToken();
        call(post("/api/v1/dashboard/discounts"), admin, "{\"name\":\"Too Much\",\"kind\":\"PERCENT\",\"value\":150,\"scope\":\"ORDER\"}")
                .andExpect(status().isBadRequest());
        String created = call(post("/api/v1/dashboard/discounts"), admin,
                "{\"name\":\"Opening Week 15%\",\"kind\":\"PERCENT\",\"value\":15,\"scope\":\"ORDER\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(created, "$.id");
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].name").value(hasItem("Opening Week 15%")));

        call(put("/api/v1/dashboard/discounts/" + id), admin,
                "{\"name\":\"Opening Week 15%\",\"kind\":\"PERCENT\",\"value\":15,\"scope\":\"ORDER\",\"requiresApproval\":true,\"active\":false}")
                .andExpect(jsonPath("$.requiresApproval").value(true))
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/v1/discounts").param("scope", "ORDER").with(cashier()))
                .andExpect(jsonPath("$[*].name").value(not(hasItem("Opening Week 15%"))));
    }

    // ---- Charts ----

    @Test
    void theOverviewTotalsTheOutletsTheUserCanSee() throws Exception {
        sellAtOutlet1();
        sellAtOutlet1();
        String admin = adminToken();
        call(get("/api/v1/dashboard/overview").param("from", TODAY).param("to", TODAY), admin, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current.netSales").value(32.0))
                .andExpect(jsonPath("$.current.orders").value(2))
                .andExpect(jsonPath("$.current.averageOrder").value(16.0))
                .andExpect(jsonPath("$.current.itemsSold").value(2))
                .andExpect(jsonPath("$.previous.orders").value(0))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].netSales").value(32.0))
                .andExpect(jsonPath("$.outlets[*].name").value(contains("Outlet 1", "Outlet 2")))
                .andExpect(jsonPath("$.hours.length()").value(24))
                .andExpect(jsonPath("$.topItems[0].name").value("Pouch Cheese"))
                .andExpect(jsonPath("$.topItems[0].quantity").value(2))
                .andExpect(jsonPath("$.paymentMethods[0].amount").value(32.0));
        // Seven days by default, every day listed.
        call(get("/api/v1/dashboard/overview"), admin, null).andExpect(jsonPath("$.days.length()").value(7));

        // A manager sees only their own outlet, and asking for another is "not found".
        String manager = managerToken();
        call(get("/api/v1/dashboard/overview").param("from", TODAY).param("to", TODAY), manager, null)
                .andExpect(jsonPath("$.current.orders").value(0))
                .andExpect(jsonPath("$.outlets[*].name").value(contains("Outlet 2")));
        call(get("/api/v1/dashboard/overview").param("outletId", "1"), manager, null).andExpect(status().isNotFound());
    }

    // ---- iPads ----

    @Test
    void headOfficeOrTheOutletsManagerResetsAnIPadWhichMustThenBeSetUpAgain() throws Exception {
        String firstKey = setUpIPad("654321", 1L);
        setUpIPad("888888", second);
        String admin = adminToken();
        String manager = managerToken();

        String devices = call(get("/api/v1/dashboard/devices"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].outletName").value(contains("Outlet 1", "Outlet 2")))
                .andExpect(jsonPath("$[0].label").value("iPad 1"))
                .andExpect(jsonPath("$[0].setUpBy").value("Test Manager"))
                .andExpect(jsonPath("$[0].deviceKey").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Integer firstId = JsonPath.read(devices, "$[0].id");
        // A manager sees, and resets, only their own outlet's.
        call(get("/api/v1/dashboard/devices"), manager, null).andExpect(jsonPath("$[*].outletName").value(contains("Outlet 2")));
        call(post("/api/v1/dashboard/devices/" + firstId + "/reset"), manager, null).andExpect(status().isNotFound());

        tillLogin(firstKey).andExpect(status().isOk());
        // One till per outlet: another iPad can't be set up there until this one is reset.
        login("/api/v1/auth/terminal-setup", "{\"pin\":\"654321\",\"outletId\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Outlet 1 already has its till (iPad 1, set up by Test Manager). "
                        + "Reset it on the dashboard (iPads) to set up this iPad instead."));
        call(post("/api/v1/dashboard/devices/" + firstId + "/reset"), admin, null).andExpect(status().isNoContent());
        tillLogin(firstKey).andExpect(status().isGone());
        String replacement = setUpIPad("654321", 1L);
        tillLogin(replacement).andExpect(status().isOk());
        call(get("/api/v1/dashboard/devices"), admin, null)
                .andExpect(jsonPath("$[*].outletName").value(contains("Outlet 1", "Outlet 2")))
                .andExpect(jsonPath("$[0].label").value("iPad 2"));
        assertThat(jdbc.queryForObject("SELECT reset_by FROM till_device WHERE id = ?", String.class, firstId))
                .isEqualTo("Head Office");
    }

    @Test
    void theTenthWrongPinLocksOnlyThatOutletsPadAndItsManagerOrHeadOfficeUnlocksIt() throws Exception {
        String firstKey = setUpIPad("654321", 1L);
        String secondKey = setUpIPad("888888", second);

        for (int attempt = 1; attempt <= 6; attempt++) {
            tillLogin(firstKey, "000000").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Invalid PIN"));
        }
        // The 7th to 9th say how many tries are left; the 10th locks the pad.
        tillLogin(firstKey, "000000").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid PIN. 3 tries left before the PIN pad locks for 5 minutes."));
        tillLogin(firstKey, "000000").andExpect(jsonPath("$.detail").value(containsString("2 tries left")));
        tillLogin(firstKey, "000000").andExpect(jsonPath("$.detail").value(containsString("1 try left")));
        tillLogin(firstKey, "000000").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.retryAfterSeconds").value(300))
                .andExpect(jsonPath("$.managerCanUnlock").value(true));
        tillLogin(firstKey, "123456").andExpect(status().isTooManyRequests());
        // The login screen asks whether it is still locked (and blocks the keypad meanwhile).
        pinLock("{\"deviceKey\":\"" + firstKey + "\"}").andExpect(jsonPath("$.lockedForSeconds").value(300));
        pinLock("{\"deviceKey\":\"" + secondKey + "\"}").andExpect(jsonPath("$.lockedForSeconds").value(0));
        pinLock("{\"deviceKey\":\"not-a-key\"}").andExpect(jsonPath("$.lockedForSeconds").value(0));
        // Another outlet's till, even from the same address, is unaffected.
        tillLogin(secondKey, "888888").andExpect(status().isOk());

        String admin = adminToken();
        String manager = managerToken();
        call(get("/api/v1/dashboard/pin-locks"), admin, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].outletName").value("Outlet 1"))
                .andExpect(jsonPath("$[0].pad").value("TILL"))
                .andExpect(jsonPath("$[0].padName").value("PIN login"))
                .andExpect(jsonPath("$[0].lockedUntil").exists());
        // A manager sees, and unlocks, only their own outlet's.
        call(get("/api/v1/dashboard/pin-locks"), manager, null).andExpect(jsonPath("$.length()").value(0));
        call(post("/api/v1/dashboard/pin-locks/1/TILL/unlock"), manager, null).andExpect(status().isNotFound());

        call(post("/api/v1/dashboard/pin-locks/1/TILL/unlock"), admin, null).andExpect(status().isNoContent());
        pinLock("{\"deviceKey\":\"" + firstKey + "\"}").andExpect(jsonPath("$.lockedForSeconds").value(0));
        tillLogin(firstKey, "123456").andExpect(status().isOk());
        call(get("/api/v1/dashboard/pin-locks"), admin, null).andExpect(jsonPath("$.length()").value(0));

        // Clock In/Out has a pad of its own.
        for (int attempt = 1; attempt <= 9; attempt++) {
            clockStatus("000000").andExpect(status().isUnprocessableContent());
        }
        clockStatus("000000").andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/v1/auth/pin-lock/CLOCK").with(cashier()))
                .andExpect(jsonPath("$.lockedForSeconds").value(300));
        mockMvc.perform(get("/api/v1/auth/pin-lock/TILL").with(cashier()))
                .andExpect(jsonPath("$.lockedForSeconds").value(0));
        // The set-up screen's pad is asked about by outlet.
        pinLock("{\"outletId\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.lockedForSeconds").value(0));
        tillLogin(firstKey, "123456").andExpect(status().isOk());
        call(get("/api/v1/dashboard/pin-locks"), admin, null).andExpect(jsonPath("$[0].padName").value("Clock In/Out"));
        call(post("/api/v1/dashboard/pin-locks/1/CLOCK/unlock"), admin, null).andExpect(status().isNoContent());
        clockStatus("123456").andExpect(status().isOk());
    }

    // ---- helpers ----

    private ResultActions tillLogin(String deviceKey, String pin) throws Exception {
        return login("/api/v1/auth/login", "{\"pin\":\"" + pin + "\",\"deviceKey\":\"" + deviceKey + "\"}");
    }

    private ResultActions pinLock(String json) throws Exception {
        return login("/api/v1/auth/pin-lock", json);
    }

    private ResultActions clockStatus(String pin) throws Exception {
        return mockMvc.perform(post("/api/v1/timecards/status").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"pin\":\"" + pin + "\"}"));
    }

    private String setUpIPad(String managerPin, long outletId) throws Exception {
        return JsonPath.read(login("/api/v1/auth/terminal-setup", "{\"pin\":\"" + managerPin + "\",\"outletId\":" + outletId + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.deviceKey");
    }

    private ResultActions tillLogin(String deviceKey) throws Exception {
        return login("/api/v1/auth/login", "{\"pin\":\"123456\",\"deviceKey\":\"" + deviceKey + "\"}");
    }

    private String managerToken() throws Exception {
        return token(login("/api/v1/dashboard/auth/login",
                "{\"email\":\"Second.Manager@counterline.test\",\"password\":\"manager pass\"}")
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.name").value("Second Manager"))
                .andExpect(jsonPath("$.outlet.name").value("Outlet 2")));
    }

    private String adminToken() throws Exception {
        return token(login("/api/v1/dashboard/auth/login", "{\"email\":\"admin@counterline.test\",\"password\":\"correct horse\"}"));
    }

    /** One Pouch Cheese (16.00) sold at outlet 1 by the cashier; returns the order id. */
    private String sellAtOutlet1() throws Exception {
        String body = mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"clientRequestId":"%s","lines":[{"itemId":1,"quantity":1}],
                         "payments":[{"paymentMethodId":2,"reference":"ref","amount":16.00}]}""".formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return String.valueOf((Integer) JsonPath.read(body, "$.id"));
    }

    private ResultActions login(String path, String json) throws Exception {
        return mockMvc.perform(post(path).with(request -> {
            request.setRemoteAddr("10.30.0." + (int) (Math.random() * 200 + 1));
            return request;
        }).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String token(ResultActions login) throws Exception {
        return JsonPath.read(login.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor cashier() {
        Long cashierId = staffRepository.findAll().stream().filter(s -> s.getRole() == StaffRole.STAFF).findFirst().orElseThrow().getId();
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
