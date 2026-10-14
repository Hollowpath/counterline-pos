package com.counterline.pos.display;

import com.counterline.pos.TestcontainersConfiguration;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The customer display end to end: the POS creates it (an access code), the display connects and
 * polls, the POS publishes its cart, and a customer logs in or signs up on the display for the
 * cashier to confirm.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CustomerDisplayIntegrationTest {

    private static final String CART = """
            {"stage":"CART","lines":[{"quantity":2,"name":"WSP Caramel","detail":null,"price":32.00,"total":32.00,"free":false},
                                     {"quantity":1,"name":"B3 F1 BC Cheese","detail":"Free","price":6.50,"total":0,"free":true}],
             "itemCount":3,"subtotal":38.50,"discount":6.50,"total":32.00,"memberName":null}""";
    /** New Sale tapped on the POS: nothing in it yet, but members can log in. */
    private static final String NEW_SALE = """
            {"stage":"CART","lines":[],"itemCount":0,"subtotal":0,"discount":0,"total":0,"memberName":null}""";
    /** No sale open: the display shows its posters. */
    private static final String IDLE = """
            {"stage":"IDLE","lines":[],"itemCount":0,"subtotal":0,"discount":0,"total":0,"memberName":null}""";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffRepository staffRepository;

    @Autowired
    PinHasher pinHasher;

    private Long cashierId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE"); // displays, orders
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        memberId = jdbc.queryForObject("""
                INSERT INTO customer (first_name, last_name, phone, created_at, updated_at)
                VALUES ('Nur', 'Aisyah', '+60112345678', now(), now()) RETURNING id
                """, Long.class);
    }

    @Test
    void theDisplayConnectsWithTheCodeFromThePosAndShowsItsCart() throws Exception {
        String created = pos(post("/api/v1/displays"), null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessCode").value(matchesPattern("\\d{6}")))
                .andExpect(jsonPath("$.connected").value(false))
                .andReturn().getResponse().getContentAsString();
        Integer displayId = JsonPath.read(created, "$.id");
        String code = JsonPath.read(created, "$.accessCode");

        connect("000000".equals(code) ? "111111" : "000000").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("Wrong access code")));
        String token = token(code);
        // A code works once.
        connect(code).andExpect(status().isUnprocessableContent());

        pos(get("/api/v1/displays/" + displayId), null)
                .andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.accessCode").doesNotExist());

        String state = display(get("/api/v1/display/state"), token, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.outletName").value("Outlet 1"))
                .andExpect(jsonPath("$.cart").doesNotExist())
                .andExpect(jsonPath("$.member.status").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(state, "$.version");
        // Nothing new: 204, so a poll every second costs almost nothing.
        display(get("/api/v1/display/state").param("since", String.valueOf(version)), token, null)
                .andExpect(status().isNoContent());

        pos(put("/api/v1/displays/" + displayId + "/cart"), CART).andExpect(status().isNoContent());
        display(get("/api/v1/display/state").param("since", String.valueOf(version)), token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cart.lines[0].name").value("WSP Caramel"))
                .andExpect(jsonPath("$.cart.lines[1].price").value(6.5))
                .andExpect(jsonPath("$.cart.lines[1].free").value(true))
                .andExpect(jsonPath("$.cart.total").value(32.0))
                .andExpect(jsonPath("$.cart.points").doesNotExist());
    }

    @Test
    void aDisplayTokenOpensOnlyTheDisplayApiAndAStaffTokenCannotUseIt() throws Exception {
        String token = token(newCode());
        mockMvc.perform(get("/api/v1/register").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/display/state").with(cashier())).andExpect(status().isForbidden());
    }

    @Test
    void aMemberLogsInByPhoneWhileASaleIsOpenAndThePosAddsThemToIt() throws Exception {
        String created = pos(post("/api/v1/displays"), null).andReturn().getResponse().getContentAsString();
        Integer displayId = JsonPath.read(created, "$.id");
        String token = token(JsonPath.read(created, "$.accessCode"));

        // Only for a sale: not while the display shows its posters.
        display(post("/api/v1/display/member-login"), token, "{\"phone\":\"0112345678\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Please wait for the cashier to start your order."));
        pos(put("/api/v1/displays/" + displayId + "/cart"), IDLE).andExpect(status().isNoContent());
        display(post("/api/v1/display/member-login"), token, "{\"phone\":\"0112345678\"}")
                .andExpect(status().isUnprocessableContent());
        pos(put("/api/v1/displays/" + displayId + "/cart"), NEW_SALE).andExpect(status().isNoContent());

        display(post("/api/v1/display/member-login"), token, "{\"phone\":\"0199999999\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No member has this phone number. Tap Register Member to join."));
        // However the number is typed.
        display(post("/api/v1/display/member-login"), token, "{\"phone\":\"011-234 5678\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.firstName").value("Nur"));

        pos(get("/api/v1/displays/" + displayId), null)
                .andExpect(jsonPath("$.waitingMember.customerId").value(memberId))
                .andExpect(jsonPath("$.waitingMember.fullName").value("Nur Aisyah"))
                .andExpect(jsonPath("$.waitingMember.phone").value("+60112345678"))
                .andExpect(jsonPath("$.waitingMember.newMember").value(false));

        pos(post("/api/v1/displays/" + displayId + "/member/confirm"), "{\"customerId\":999}")
                .andExpect(status().isUnprocessableContent());
        pos(post("/api/v1/displays/" + displayId + "/member/confirm"), "{\"customerId\":" + memberId + "}")
                .andExpect(status().isNoContent());
        display(get("/api/v1/display/state"), token, null)
                .andExpect(jsonPath("$.member.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.member.firstName").value("Nur"));
        pos(get("/api/v1/displays/" + displayId), null).andExpect(jsonPath("$.waitingMember").doesNotExist());

        // The sale ends: the next customer starts afresh.
        pos(put("/api/v1/displays/" + displayId + "/cart"), IDLE).andExpect(status().isNoContent());
        display(get("/api/v1/display/state"), token, null).andExpect(jsonPath("$.member.status").value("NONE"));
    }

    @Test
    void aNewMemberSignsUpOnTheDisplayWithoutSeeingAnyoneElsesDetails() throws Exception {
        String created = pos(post("/api/v1/displays"), null).andReturn().getResponse().getContentAsString();
        Integer displayId = JsonPath.read(created, "$.id");
        String token = token(JsonPath.read(created, "$.accessCode"));
        pos(put("/api/v1/displays/" + displayId + "/cart"), CART).andExpect(status().isNoContent());

        display(post("/api/v1/display/members"), token, "{\"phone\":\"0112345678\",\"firstName\":\"Someone\",\"lastName\":\"Else\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This phone number is already a member. Tap Login instead."))
                .andExpect(jsonPath("$.detail").value(not(containsString("Aisyah"))));
        display(post("/api/v1/display/members"), token, "{\"phone\":\"\",\"firstName\":\"Someone\",\"lastName\":\"Else\"}")
                .andExpect(status().isBadRequest());

        display(post("/api/v1/display/members"), token, """
                {"phone":"0127778888","firstName":"Lim","lastName":"Wei","email":"lim@example.com","gender":"MALE",
                 "dateOfBirth":"1995-04-01","marketingEmail":true,"marketingSms":false,"marketingCall":false}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.firstName").value("Lim"));
        pos(get("/api/v1/displays/" + displayId), null)
                .andExpect(jsonPath("$.waitingMember.fullName").value("Lim Wei"))
                .andExpect(jsonPath("$.waitingMember.newMember").value(true));

        // The sale ended before the till added them: they were for that sale.
        pos(put("/api/v1/displays/" + displayId + "/cart"), IDLE).andExpect(status().isNoContent());
        pos(get("/api/v1/displays/" + displayId), null).andExpect(jsonPath("$.waitingMember").doesNotExist());
        display(get("/api/v1/display/state"), token, null).andExpect(jsonPath("$.member.status").value("NONE"));
    }

    @Test
    void aDisconnectedDisplayIsLoggedOutAndOtherOutletsCannotReachIt() throws Exception {
        String created = pos(post("/api/v1/displays"), null).andReturn().getResponse().getContentAsString();
        Integer displayId = JsonPath.read(created, "$.id");
        String token = token(JsonPath.read(created, "$.accessCode"));

        mockMvc.perform(get("/api/v1/displays/" + displayId)
                        .with(jwt().jwt(t -> t.claim("outlet", 99L).subject(cashierId.toString()))))
                .andExpect(status().isNotFound());

        pos(delete("/api/v1/displays/" + displayId), null).andExpect(status().isNoContent());
        display(get("/api/v1/display/state"), token, null).andExpect(status().isUnauthorized());
        pos(get("/api/v1/displays/" + displayId), null).andExpect(status().isNotFound());
    }

    // ---- helpers ----

    private String newCode() throws Exception {
        return JsonPath.read(pos(post("/api/v1/displays"), null).andReturn().getResponse().getContentAsString(),
                "$.accessCode");
    }

    private ResultActions connect(String code) throws Exception {
        return mockMvc.perform(post("/api/v1/display/connect")
                .with(request -> {
                    request.setRemoteAddr("10.20.0." + (int) (Math.random() * 200 + 1));
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON).content("{\"accessCode\":\"" + code + "\"}"));
    }

    private String token(String code) throws Exception {
        return JsonPath.read(connect(code).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.token");
    }

    private ResultActions pos(MockHttpServletRequestBuilder request, String json) throws Exception {
        request.with(cashier());
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private ResultActions display(MockHttpServletRequestBuilder request, String token, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
