package com.counterline.pos.staff;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Staff administration and timecard corrections are recorded against a stable actor (type and id), and the record holds
 * no PIN, password or e-mail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ActorAttributionIntegrationTest {

    private static final String STAFF = "/api/v1/dashboard/admin/staff";
    private static final long ADMIN_ID = 7;
    private static final String PASSWORD = "Correct-" + UUID.randomUUID();
    private static final String NEW_PASSWORD = "Another-" + UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StaffRepository staffRepository;
    @Autowired
    PinHasher pinHasher;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE login_throttle, staff, customer RESTART IDENTITY CASCADE");
    }

    // ---- staff administration ----

    @Test
    void everyStaffChangeIsRecordedWithTheAdminsStableIdTheTargetTheActionAndTheTime() throws Exception {
        Instant before = databaseNow();
        // A second manager, so the first may be deactivated.
        long spare = created(post(STAFF), admin(), "{\"fullName\":\"Spare Manager\",\"role\":\"MANAGER\",\"outletId\":1,\"pin\":\"246810\"}");
        ResultActions made = call(post(STAFF), admin(), "{\"fullName\":\"Audited Manager\",\"role\":\"MANAGER\",\"outletId\":1,"
                + "\"pin\":\"654321\",\"email\":\"audited@example.test\",\"password\":\"" + PASSWORD + "\"}").andExpect(status().isCreated());
        long id = idOf(made);
        long version = versionOf(made);

        ResultActions renamed = call(put(STAFF + "/" + id), admin(), "{\"fullName\":\"Audited Renamed\",\"role\":\"MANAGER\","
                + "\"outletId\":1,\"version\":" + version + "}").andExpect(status().isOk());
        version = versionOf(renamed);
        call(put(STAFF + "/" + id), admin(), "{\"fullName\":\"Audited Renamed\",\"role\":\"MANAGER\","
                + "\"outletId\":1,\"password\":\"" + NEW_PASSWORD + "\",\"version\":" + version + "}").andExpect(status().isOk());
        call(post(STAFF + "/" + id + "/pin"), admin(), "{\"pin\":\"135790\"}").andExpect(status().isOk());
        call(delete(STAFF + "/" + id + "/dashboard-login"), admin(), null).andExpect(status().isOk());
        call(post(STAFF + "/" + id + "/deactivate"), admin(), "{}").andExpect(status().isOk());
        call(post(STAFF + "/" + id + "/reactivate"), admin(), "{}").andExpect(status().isOk());
        Instant after = databaseNow();

        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM staff_audit WHERE staff_id = ? ORDER BY id", id);
        assertThat(rows).extracting(row -> row.get("action")).containsExactly("CREATE", "LOGIN_CHANGED", "EDIT", "LOGIN_CHANGED",
                "PIN_RESET", "LOGIN_REMOVED", "DEACTIVATE", "REACTIVATE");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("actor_type")).isEqualTo("ADMIN");
            assertThat(row.get("actor_id")).isEqualTo(ADMIN_ID);
            assertThat(((java.sql.Timestamp) row.get("occurred_at")).toInstant()).isBetween(before, after);
        });
        // The other person's creation is recorded against them, not against the first.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_audit WHERE staff_id = ?", Long.class, spare)).isEqualTo(1);

        // Nothing secret reaches the record: no PIN, password or e-mail, in any column.
        String everything = jdbc.queryForList("SELECT * FROM staff_audit").toString();
        assertThat(everything).doesNotContain("654321", "135790", "246810", PASSWORD, NEW_PASSWORD, "audited@example.test");
    }

    @Test
    void changesThatDoNothingOrAreRefusedLeaveNoRecord() throws Exception {
        long id = created(post(STAFF), admin(), "{\"fullName\":\"Cashier\",\"role\":\"STAFF\",\"outletId\":1,\"pin\":\"111222\"}");
        long recorded = audits();

        call(post(STAFF + "/" + id + "/reactivate"), admin(), "{}").andExpect(status().isOk()); // already active
        call(delete(STAFF + "/" + id + "/dashboard-login"), admin(), null).andExpect(status().isOk()); // has none
        call(post(STAFF), admin(), "{\"fullName\":\"Copy\",\"role\":\"STAFF\",\"outletId\":1,\"pin\":\"111222\"}")
                .andExpect(status().isConflict()); // PIN taken
        call(post(STAFF + "/" + id + "/pin"), admin(), "{\"pin\":\"abc\"}").andExpect(status().isBadRequest());

        assertThat(audits()).isEqualTo(recorded);
    }

    @Test
    void aManagerCannotAdministerStaffAndLeavesNoRecord() throws Exception {
        long id = created(post(STAFF), admin(), "{\"fullName\":\"Cashier\",\"role\":\"STAFF\",\"outletId\":1,\"pin\":\"111222\"}");
        long recorded = audits();

        call(post(STAFF + "/" + id + "/deactivate"), manager(3, 1), "{}").andExpect(status().isForbidden());
        call(post(STAFF + "/" + id + "/pin"), manager(3, 1), "{\"pin\":\"999888\"}").andExpect(status().isForbidden());
        mockMvc.perform(post(STAFF + "/" + id + "/deactivate").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        assertThat(audits()).isEqualTo(recorded);
        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void anAdminTokenWithoutAnAccountIdIsNotAnActorAndChangesNothing() throws Exception {
        long id = created(post(STAFF), admin(), "{\"fullName\":\"Cashier\",\"role\":\"STAFF\",\"outletId\":1,\"pin\":\"111222\"}");

        call(post(STAFF + "/" + id + "/deactivate"), jwt().jwt(token -> token.subject("admin").claim("role", "ADMIN")
                        .claim("name", "Head Office").claim("channel", "dashboard")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")), "{}")
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT active FROM staff WHERE id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void theRecordIsAppendOnly() throws Exception {
        long id = created(post(STAFF), admin(), "{\"fullName\":\"Cashier\",\"role\":\"STAFF\",\"outletId\":1,\"pin\":\"111222\"}");

        assertThatThrownBy(() -> jdbc.update("UPDATE staff_audit SET actor_id = 99 WHERE staff_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM staff_audit WHERE staff_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(audits()).isEqualTo(1);
    }

    // ---- timecard corrections ----

    @Test
    void aCorrectionStoresTheCorrectorsStableIdentityBesideTheDisplayName() throws Exception {
        long manager = staffRepository.save(new Staff("Shift Manager", pinHasher.hash("333444"), StaffRole.MANAGER, 1L)).getId();
        long cashier = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("555666"), StaffRole.STAFF, 1L)).getId();
        String close = "/api/v1/dashboard/outlets/1/timecards/" + cashier + "/close";
        String body = "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"reason\":\"Forgot\"}";

        shift(cashier, 3);
        call(post(close), manager(manager, 1), body).andExpect(status().isOk()).andExpect(jsonPath("$.correctedBy").value("Shift Manager"));
        shift(cashier, 2);
        call(post(close), admin(), body).andExpect(status().isOk()).andExpect(jsonPath("$.correctedBy").value("Head Office"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT corrected_by, corrected_by_type, corrected_by_id FROM timecard_event WHERE corrected_by IS NOT NULL ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("corrected_by", "Shift Manager").containsEntry("corrected_by_type", "MANAGER")
                .containsEntry("corrected_by_id", manager);
        assertThat(rows.get(1)).containsEntry("corrected_by", "Head Office").containsEntry("corrected_by_type", "ADMIN")
                .containsEntry("corrected_by_id", ADMIN_ID);
        // The display name is as it was then: renaming the manager later does not rewrite it.
        jdbc.update("UPDATE staff SET full_name = 'Renamed Manager' WHERE id = ?", manager);
        assertThat(jdbc.queryForObject("SELECT corrected_by FROM timecard_event WHERE corrected_by_id = ? AND corrected_by_type = 'MANAGER'",
                String.class, manager)).isEqualTo("Shift Manager");
    }

    @Test
    void aCorrectionByAnUnidentifiableTokenIsRefusedAndWritesNothing() throws Exception {
        long cashier = staffRepository.save(new Staff("Test Cashier", pinHasher.hash("555666"), StaffRole.STAFF, 1L)).getId();
        shift(cashier, 3);

        call(post("/api/v1/dashboard/outlets/1/timecards/" + cashier + "/close"), jwt().jwt(token -> token.subject("not-a-number")
                        .claim("role", "MANAGER").claim("outlet", 1L).claim("name", "Odd").claim("channel", "dashboard"))
                        .authorities(new SimpleGrantedAuthority("ROLE_MANAGER")),
                "{\"outAt\":\"" + Instant.now().minusSeconds(60) + "\",\"reason\":\"Forgot\"}").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM timecard_event WHERE corrected_by IS NOT NULL", Long.class)).isZero();
    }

    // ---- helpers ----

    /** The staff member clocks in {@code hoursAgo} hours ago and is still in. */
    private void shift(long staffId, int hoursAgo) {
        jdbc.update("INSERT INTO timecard_event (staff_id, type, occurred_at, outlet_id) VALUES (?, 'IN', now() - make_interval(hours => ?), 1)",
                staffId, hoursAgo);
    }

    private long audits() {
        return jdbc.queryForObject("SELECT count(*) FROM staff_audit", Long.class);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class).toInstant().truncatedTo(ChronoUnit.MICROS);
    }

    private long created(MockHttpServletRequestBuilder request, RequestPostProcessor as, String json) throws Exception {
        return idOf(call(request, as, json).andExpect(status().isCreated()));
    }

    private static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static long versionOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.version")).longValue();
    }

    private ResultActions call(MockHttpServletRequestBuilder request, RequestPostProcessor as, String json) throws Exception {
        MockHttpServletRequestBuilder builder = request.with(as);
        if (json != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(builder);
    }

    private static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject("admin-" + ADMIN_ID).claim("role", "ADMIN").claim("name", "Head Office")
                        .claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor manager(long staffId, long outlet) {
        return jwt().jwt(token -> token.subject(Long.toString(staffId)).claim("role", "MANAGER").claim("outlet", outlet)
                        .claim("name", "Shift Manager").claim("channel", "dashboard"))
                .authorities(new SimpleGrantedAuthority("ROLE_MANAGER"));
    }}
