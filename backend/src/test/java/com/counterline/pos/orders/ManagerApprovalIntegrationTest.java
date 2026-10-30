package com.counterline.pos.orders;

import com.counterline.pos.TestRegister;
import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.ApprovalOperation;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.TokenService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A manager's approval of a department comp ("Special Access Required") travels in the sale's body. It
 * is for the DISCOUNT operation only, and authorises one completed sale (however many comped lines the
 * sale holds), whether the sale is checked out or an edited order is paid. It is spent in the sale's own
 * transaction, so a sale that fails leaves it usable and two sales racing for it cannot both succeed.
 * It is good only while the manager's session is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Sql({"/catalog-fixture.sql", "/options-fixture.sql"})
class ManagerApprovalIntegrationTest {

    private static final String NEEDED = "Manager approval needed";
    private static final String USED = "Approval used";
    /** One line of three units, comped by a discount that needs approval (a sale holds one discount at most). */
    private static final String COMPED_LINE = """
            [{"itemId":1,"quantity":3,"discountIds":[%d]}]""";

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
    JwtDecoder jwtDecoder;

    private Long cashierId;
    private Staff manager;
    private long lineComp;
    private String lines;

    @BeforeEach
    void aCashierAManagerAndADiscountThatNeedsApproval() {
        jdbc.execute("TRUNCATE login_throttle, used_approval_token, staff, customer RESTART IDENTITY CASCADE");
        cashierId = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("123456"), StaffRole.STAFF, 1L)).getId();
        manager = staffRepository.save(new Staff("Test Manager", pinHasher.hash("654321"), StaffRole.MANAGER, 1L));
        TestRegister.open(jdbc, cashierId);
        lineComp = jdbc.queryForObject("""
                INSERT INTO discount (name, kind, value, scope, sort_order, requires_approval)
                VALUES ('Dept Line Comp', 'PERCENT', 100.00, 'ITEM', 6, TRUE) RETURNING id""", Long.class);
        lines = COMPED_LINE.formatted(lineComp);
    }

    // ---- One approval, one completed sale ----

    @Test
    void oneApprovalPaysOneCompedSaleAndNoSecond() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);

        UUID first = UUID.randomUUID();
        checkout(first, lines, approval).andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(0.0))
                .andExpect(jsonPath("$.lines[0].quantity").value(3))
                .andExpect(jsonPath("$.lines[0].discounts[0].detail").value("Approved by Test Manager"));
        assertThat(count("SELECT count(*) FROM sale_order_discount WHERE approved_by IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM used_approval_token")).isEqualTo(1);

        // The same approval for a different sale is refused, and that sale is not recorded.
        checkout(UUID.randomUUID(), lines, approval).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(USED));
        assertThat(count("SELECT count(*) FROM sale_order")).isEqualTo(1);
    }

    @Test
    void retryingACompletedSaleReturnsItEvenThoughItsApprovalIsSpent() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);
        UUID request = UUID.randomUUID();

        long id = id(checkout(request, lines, approval).andExpect(status().isCreated()));
        long retried = id(checkout(request, lines, approval).andExpect(status().isOk()));

        assertThat(retried).isEqualTo(id);
        assertThat(count("SELECT count(*) FROM sale_order")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM used_approval_token")).isEqualTo(1);
    }

    @Test
    void aSaleThatFailsLeavesItsApprovalUsable() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);

        // The register is closed: the sale is refused after the approval was checked, and rolls back.
        TestRegister.close(jdbc);
        checkout(UUID.randomUUID(), lines, approval).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Register closed"));
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();
        assertThat(count("SELECT count(*) FROM sale_order")).isZero();

        // A payment that does not cover the sale fails the same way.
        TestRegister.open(jdbc, cashierId);
        mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON).content(body(
                        UUID.randomUUID(), "PAY", lines, "[{\"paymentMethodId\":2,\"reference\":\"ref\",\"amount\":500.00}]", approval)))
                .andExpect(status().is4xxClientError());
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();

        // The very approval then pays the sale.
        checkout(UUID.randomUUID(), lines, approval).andExpect(status().isCreated());
        assertThat(count("SELECT count(*) FROM used_approval_token")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoSalesRacingForOneApprovalCannotBothSucceed() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);

        List<Integer> statuses = together(List.of(
                () -> checkout(UUID.randomUUID(), lines, approval).andReturn().getResponse().getStatus(),
                () -> checkout(UUID.randomUUID(), lines, approval).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(201, 403);
        assertThat(count("SELECT count(*) FROM sale_order")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM used_approval_token")).isEqualTo(1);
    }

    @RepeatedTest(3)
    void twoCopiesOfOneRequestRacingBothGetTheOneSale() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);
        UUID request = UUID.randomUUID();

        List<MvcResult> results = together(List.of(
                () -> checkout(request, lines, approval).andReturn(),
                () -> checkout(request, lines, approval).andReturn()));

        List<Long> ids = new ArrayList<>();
        for (MvcResult result : results) {
            assertThat(result.getResponse().getStatus()).isIn(200, 201);
            ids.add(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue());
        }
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(count("SELECT count(*) FROM sale_order")).isEqualTo(1);
    }

    // ---- Edit Order -> Pay ----

    @Test
    void payingAnEditedOrderSpendsTheApprovalToo() throws Exception {
        long firstOrder = placed();
        long secondOrder = placed();
        String approval = approval(ApprovalOperation.DISCOUNT);
        UUID request = UUID.randomUUID();

        edit(firstOrder, request, approval).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(count("SELECT count(*) FROM used_approval_token")).isEqualTo(1);

        // Not for another order, whether that is paid by edit or checked out...
        edit(secondOrder, UUID.randomUUID(), approval).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(USED));
        checkout(UUID.randomUUID(), lines, approval).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, secondOrder)).isEqualTo("OPEN");

        // ...while a retry of the paid edit returns the paid order, not a refusal.
        edit(firstOrder, request, approval).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(firstOrder));
    }

    @Test
    void anEditedOrderThatCannotBePaidLeavesTheApprovalUsable() throws Exception {
        long order = placed();
        String approval = approval(ApprovalOperation.DISCOUNT);

        TestRegister.close(jdbc);
        edit(order, UUID.randomUUID(), approval).andExpect(status().isUnprocessableContent());
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sale_order WHERE id = ?", String.class, order)).isEqualTo("OPEN");

        TestRegister.open(jdbc, cashierId);
        edit(order, UUID.randomUUID(), approval).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @RepeatedTest(3)
    void twoEditedOrdersRacingForOneApprovalCannotBothBePaid() throws Exception {
        long a = placed();
        long b = placed();
        String approval = approval(ApprovalOperation.DISCOUNT);

        List<Integer> statuses = together(List.of(
                () -> edit(a, UUID.randomUUID(), approval).andReturn().getResponse().getStatus(),
                () -> edit(b, UUID.randomUUID(), approval).andReturn().getResponse().getStatus()));

        assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        assertThat(count("SELECT count(*) FROM sale_order WHERE status = 'CLOSED'")).isEqualTo(1);
    }

    // ---- The operation ----

    @Test
    void onlyADiscountApprovalApprovesADiscount() throws Exception {
        for (ApprovalOperation other : List.of(ApprovalOperation.VOID, ApprovalOperation.REFUND)) {
            checkout(UUID.randomUUID(), lines, approval(other)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(NEEDED));
        }
        // Nor does an approval with no operation, an unknown one or one in other letters, as old tokens had.
        for (Object operation : Arrays.asList(null, "PAYOUT", "discount", "", 7)) {
            String token = approvalWithOperation(operation);
            checkout(UUID.randomUUID(), lines, token).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(NEEDED));
            assertThat(count("SELECT count(*) FROM used_approval_token WHERE token_id = '" + jwtDecoder.decode(token).getId() + "'"))
                    .isZero();
        }
        assertThat(count("SELECT count(*) FROM sale_order")).isZero();
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();

        // A refused wrong-operation approval is not spent: the discount one beside it still works.
        checkout(UUID.randomUUID(), lines, approval(ApprovalOperation.DISCOUNT)).andExpect(status().isCreated());
    }

    // ---- The manager's session ----

    @Test
    void anApprovalMustBeForAManagerRole() throws Exception {
        // Otherwise correct, but not a manager's role claim: the session check would let it by.
        checkout(UUID.randomUUID(), lines, approvalWithClaims("DISCOUNT", "STAFF")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(NEEDED));
        checkout(UUID.randomUUID(), lines, approvalWithClaims("DISCOUNT", "ADMIN")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(NEEDED));
        assertThat(count("SELECT count(*) FROM sale_order")).isZero();
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();
    }

    @Test
    void anApprovalEndsWithTheManagersSession() throws Exception {
        for (String change : List.of(
                "auth_session_version = auth_session_version + 1", // a PIN reset, role or outlet change or login change
                "active = FALSE",                                    // a deactivation
                "role = 'STAFF', auth_session_version = auth_session_version + 1")) {
            String approval = approval(ApprovalOperation.DISCOUNT);
            jdbc.update("UPDATE staff SET " + change + " WHERE id = ?", manager.getId());

            checkout(UUID.randomUUID(), lines, approval).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(NEEDED));
            assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();

            jdbc.update("UPDATE staff SET active = TRUE, role = 'MANAGER', auth_session_version = auth_session_version + 1 WHERE id = ?",
                    manager.getId());
            // Putting the account back does not bring the old approval back.
            checkout(UUID.randomUUID(), lines, approval).andExpect(status().isForbidden());
            manager = staffRepository.findById(manager.getId()).orElseThrow();
        }
        assertThat(count("SELECT count(*) FROM sale_order")).isZero();
    }

    @Test
    void anApprovalEndsWhenTheManagersOutletCloses() throws Exception {
        String approval = approval(ApprovalOperation.DISCOUNT);
        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = 1");
        try {
            checkout(UUID.randomUUID(), lines, approval).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.detail").value(NEEDED));
        } finally {
            jdbc.update("UPDATE outlet SET active = TRUE WHERE id = 1");
        }
        assertThat(count("SELECT count(*) FROM used_approval_token")).isZero();
    }

    // ---- helpers ----

    /** Runs the tasks at once and collects their results; every wait is bounded, so a stuck one fails instead of hanging. */
    private static <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> outcomes = new ArrayList<>();
            for (Callable<T> task : tasks) {
                outcomes.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> outcome : outcomes) {
                results.add(outcome.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private String approval(ApprovalOperation operation) {
        return tokenService.issueApproval(manager, 1L, Duration.ofMinutes(5), operation).value();
    }

    /** A manager's approval token as {@code operation} says (absent when null), signed like the real ones. */
    private String approvalWithOperation(Object operation) {
        return approvalWithClaims(operation, "MANAGER");
    }

    private String approvalWithClaims(Object operation, String role) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().issuer("counterline-pos").id(UUID.randomUUID().toString())
                .subject(manager.getId().toString()).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("name", manager.getFullName()).claim("role", role).claim("outlet", 1L)
                .claim("channel", "approval").claim("sver", manager.getAuthSessionVersion());
        if (operation != null) {
            claims.claim("operation", operation);
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    private org.springframework.test.web.servlet.ResultActions checkout(UUID request, String cartLines, String approval)
            throws Exception {
        return mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                .content(body(request, "PAY", cartLines, "[]", approval)));
    }

    /** Edit Order -> Pay: the placed order becomes the comped cart and is paid. */
    private org.springframework.test.web.servlet.ResultActions edit(long order, UUID request, String approval)
            throws Exception {
        MockHttpServletRequestBuilder put = put("/api/v1/orders/" + order);
        return mockMvc.perform(put.with(cashier()).contentType(MediaType.APPLICATION_JSON)
                .content(body(request, "PAY", lines, "[]", approval)));
    }

    /** An unpaid order, placed with the comped lines. */
    private long placed() throws Exception {
        return id(mockMvc.perform(post("/api/v1/orders").with(cashier()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "PLACE_ORDER", lines, "[]", null)))
                .andExpect(status().isCreated()));
    }

    private static String body(UUID request, String mode, String cartLines, String payments, String approval) {
        return """
                {"clientRequestId":"%s","mode":"%s","lines":%s,"payments":%s,"approvalToken":%s}"""
                .formatted(request, mode, cartLines, payments, approval == null ? "null" : "\"" + approval + "\"");
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
