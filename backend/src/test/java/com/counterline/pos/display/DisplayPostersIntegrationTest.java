package com.counterline.pos.display;

import com.counterline.pos.TestcontainersConfiguration;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.TokenService;
import com.counterline.pos.display.DisplayDtos.Poster;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which promotional posters the customer display may show: a closed set of keys for the promotions that
 * are active and run today in the outlet's time zone, and nothing else. Every test rolls back, so the
 * promotions and items it sets up (and the ones it switches off) are gone afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class DisplayPostersIntegrationTest {

    /** Noon on Tuesday 10 March 2026 in Kuala Lumpur (UTC+8). */
    private static final Instant NOON = Instant.parse("2026-03-10T04:00:00Z");
    private static final Set<String> KEYS = Set.of("BUY_THREE", "SEASONAL", "MEMBER_POINTS", "BIRTHDAY", "SPEND_MEMBERS", "SPEND_ANYONE");

    @Autowired
    DisplayPosterService posters;

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

    private Long cashierId;
    private Long folderId;
    private int serial;

    @BeforeEach
    void setUp() {
        jdbc.update("UPDATE promotion SET active = FALSE");
        jdbc.update("UPDATE item SET seasonal = FALSE");
        cashierId = staffRepository.save(new Staff("Poster Cashier", pinHasher.hash("480517"), StaffRole.STAFF, 1L)).getId();
        folderId = jdbc.queryForObject("""
                INSERT INTO category (id, name) OVERRIDING SYSTEM VALUE VALUES (900001, 'Poster test folder') RETURNING id
                """, Long.class);
    }

    // ---- Which promotions have a poster ----

    @Test
    void withNothingRunningThereIsNoPosterAndTheAnswerLastsFiveMinutes() {
        var answer = posters.at(NOON);
        assertThat(answer.posters()).isEmpty();
        assertThat(answer.validUntil()).isEqualTo(NOON.plus(Duration.ofMinutes(5)));
    }

    @Test
    void eachRunningPromotionAdvertisesItsOwnPosterAndNoOtherTypeHasOne() {
        promotion("buy", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, null);
        promotion("season", "BUY_X_GET_FREE", "buy_quantity", 3, true, true, null, null);
        seasonalItem(null, null);
        promotion("birthday", "BIRTHDAY_REWARD", "buy_quantity", 3, false, true, null, null);
        promotion("points", "POINTS_REDEMPTION", "points_cost", 7, false, true, null, null);
        promotion("tote", "MIN_SPEND", "min_spend", 150, false, true, null, null);
        promotion("bag", "MIN_SPEND", "min_spend", 50, false, false, null, null);
        // These two have no poster at all.
        promotion("welcome", "REGISTRATION_REWARD", null, null, false, true, null, null);
        promotion("review", "REVIEW_REWARD", null, null, false, true, null, null);

        assertThat(posters.at(NOON).posters()).containsExactly(Poster.BUY_THREE, Poster.SEASONAL, Poster.MEMBER_POINTS,
                Poster.BIRTHDAY, Poster.SPEND_MEMBERS, Poster.SPEND_ANYONE);
    }

    @Test
    void anInactivePromotionHasNoPoster() {
        promotion("buy", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, null);
        jdbc.update("UPDATE promotion SET active = FALSE");
        assertThat(posters.at(NOON).posters()).isEmpty();
    }

    @Test
    void aPromotionHasAPosterOnlyOnItsDatesBothEndsIncluded() {
        promotion("opens-tomorrow", "POINTS_REDEMPTION", "points_cost", 7, false, true, "2026-03-11", null);
        promotion("ended-yesterday", "MIN_SPEND", "min_spend", 150, false, true, null, "2026-03-09");
        assertThat(posters.at(NOON).posters()).isEmpty();

        promotion("opens-today", "BIRTHDAY_REWARD", "buy_quantity", 3, false, true, "2026-03-10", null);
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.BIRTHDAY);

        jdbc.update("DELETE FROM promotion WHERE name LIKE 'Poster test opens-today'");
        promotion("ends-today", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, "2026-03-10");
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.BUY_THREE);

        promotion("window", "POINTS_REDEMPTION", "points_cost", 7, false, true, "2026-03-10", "2026-03-10");
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.BUY_THREE, Poster.MEMBER_POINTS);
    }

    // ---- The seasonal poster also needs a seasonal item on sale ----

    @Test
    void theSeasonalPosterNeedsASeasonalItemOnSaleToday() {
        promotion("season", "BUY_X_GET_FREE", "buy_quantity", 3, true, true, null, null);
        assertThat(posters.at(NOON).posters()).as("no seasonal item at all").isEmpty();

        seasonalItem("2026-03-11", null);
        seasonalItem(null, "2026-03-09");
        assertThat(posters.at(NOON).posters()).as("one not yet, one over").isEmpty();

        long sold = seasonalItem("2026-03-01", "2026-03-31");
        jdbc.update("UPDATE item SET active = FALSE WHERE id = ?", sold);
        assertThat(posters.at(NOON).posters()).as("in its window, but off sale").isEmpty();

        jdbc.update("UPDATE item SET active = TRUE, seasonal = FALSE WHERE id = ?", sold);
        assertThat(posters.at(NOON).posters()).as("in its window, but not marked seasonal").isEmpty();

        jdbc.update("UPDATE item SET seasonal = TRUE WHERE id = ?", sold);
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.SEASONAL);
    }

    @Test
    void aSeasonalItemIsOnSaleOnBothEdgesOfItsWindowAndWithoutEnds() {
        promotion("season", "BUY_X_GET_FREE", "buy_quantity", 3, true, true, null, null);
        long item = seasonalItem("2026-03-10", "2026-03-10");
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.SEASONAL);
        jdbc.update("UPDATE item SET seasonal_from = NULL, seasonal_until = NULL WHERE id = ?", item);
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.SEASONAL);
    }

    @Test
    void aSeasonalItemWithoutTheSeasonalPromotionAdvertisesNothing() {
        promotion("buy", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, null);
        seasonalItem(null, null);
        assertThat(posters.at(NOON).posters()).containsExactly(Poster.BUY_THREE);
    }

    // ---- The outlet's day, not UTC's and not the browser's ----

    @Test
    void todayIsTheOutletsDayAndTheAnswerEndsAtItsNextMidnight() {
        promotion("ends-march-10", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, "2026-03-10");
        promotion("starts-march-11", "POINTS_REDEMPTION", "points_cost", 7, false, true, "2026-03-11", null);

        // 23:59 on 10 March in Kuala Lumpur: still the 10th, and the day ends in a minute.
        var lastMinute = posters.at(Instant.parse("2026-03-10T15:59:00Z"));
        assertThat(lastMinute.posters()).containsExactly(Poster.BUY_THREE);
        assertThat(lastMinute.validUntil()).isEqualTo(Instant.parse("2026-03-10T16:00:00Z"));

        // Midnight there: the 11th has begun in Kuala Lumpur, though it is still the 10th in UTC.
        var nextDay = posters.at(Instant.parse("2026-03-10T16:00:00Z"));
        assertThat(nextDay.posters()).containsExactly(Poster.MEMBER_POINTS);
        assertThat(nextDay.validUntil()).isEqualTo(Instant.parse("2026-03-10T16:05:00Z"));

        // Late evening UTC on the 10th is already the 11th in Kuala Lumpur.
        assertThat(posters.at(Instant.parse("2026-03-10T20:00:00Z")).posters()).containsExactly(Poster.MEMBER_POINTS);
        // Early morning UTC on the 10th is the 10th there too (08:00).
        assertThat(posters.at(Instant.parse("2026-03-10T00:00:00Z")).posters()).containsExactly(Poster.BUY_THREE);
    }

    @Test
    void theAnswerLastsFiveMinutesOrUntilMidnightWhicheverIsFirst() {
        assertThat(posters.at(Instant.parse("2026-03-10T15:54:59Z")).validUntil()).isEqualTo(Instant.parse("2026-03-10T15:59:59Z"));
        assertThat(posters.at(Instant.parse("2026-03-10T15:55:00Z")).validUntil()).isEqualTo(Instant.parse("2026-03-10T16:00:00Z"));
        assertThat(posters.at(Instant.parse("2026-03-10T15:55:01Z")).validUntil()).isEqualTo(Instant.parse("2026-03-10T16:00:00Z"));
    }

    // ---- The endpoint ----

    @Test
    void onlyAConnectedDisplayMayAsk() throws Exception {
        mockMvc.perform(get("/api/v1/display/posters")).andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        // A till's (staff) token is not a display's.
        mockMvc.perform(get("/api/v1/display/posters").with(cashier())).andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
        // Nor is a made-up bearer.
        mockMvc.perform(get("/api/v1/display/posters").header(HttpHeaders.AUTHORIZATION, "Bearer nonsense"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void theAnswerIsKeysAndAnExpiryOnlyAndIsNeverCached() throws Exception {
        promotion("buy", "BUY_X_GET_FREE", "buy_quantity", 3, false, true, null, null);
        promotion("welcome", "REGISTRATION_REWARD", null, null, false, true, null, null);
        jdbc.update("UPDATE promotion SET name = 'Internal staffroom promotion 7731' WHERE name = 'Poster test welcome'");
        String token = connectedDisplay();

        Instant before = Instant.now();
        MvcResult result = mockMvc.perform(get("/api/v1/display/posters").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        Instant after = Instant.now();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        Map<String, Object> parsed = JsonPath.read(body, "$");
        assertThat(parsed.keySet()).containsExactlyInAnyOrder("posters", "validUntil");
        List<String> keys = JsonPath.read(body, "$.posters");
        assertThat(keys).containsExactly("BUY_THREE");
        assertThat(KEYS).containsAll(keys);
        // Nothing about the promotions themselves, nor ids, members or sales.
        assertThat(body).doesNotContain("Internal staffroom").doesNotContain("Poster test")
                .doesNotContain("member").doesNotContain("sale").doesNotContain("promotion");

        Instant validUntil = Instant.parse(JsonPath.read(body, "$.validUntil"));
        assertThat(validUntil).isBetween(before.plus(Duration.ofMinutes(5)).minusSeconds(1), after.plus(Duration.ofMinutes(5)).plusSeconds(1))
                .as("or the outlet's midnight, if that comes first");
    }

    @Test
    void aDisconnectedDisplayOrAClosedOutletIsRefused() throws Exception {
        String created = mockMvc.perform(post("/api/v1/displays").with(cashier())).andReturn().getResponse().getContentAsString();
        Integer displayId = JsonPath.read(created, "$.id");
        String token = tokenOf(displayId);
        mockMvc.perform(get("/api/v1/display/posters").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        jdbc.update("UPDATE outlet SET active = FALSE WHERE id = 1");
        mockMvc.perform(get("/api/v1/display/posters").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.detail").value("Display unavailable"));
        jdbc.update("UPDATE outlet SET active = TRUE WHERE id = 1");

        mockMvc.perform(delete("/api/v1/displays/" + displayId).with(cashier())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/display/posters").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    // ---- helpers ----

    /** A promotion named "Poster test {label}"; {@code param}/{@code value} is its type's one parameter. */
    private void promotion(String label, String type, String param, Integer value, boolean seasonalOnly, boolean membersOnly,
                           String startsOn, String endsOn) {
        jdbc.update("""
                INSERT INTO promotion (name, type, buy_quantity, min_spend, points_cost, seasonal_items_only, members_only,
                                       starts_on, ends_on, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::date, ?::date, TRUE)
                """, "Poster test " + label, type,
                "buy_quantity".equals(param) ? value : null,
                "min_spend".equals(param) ? value : null,
                "points_cost".equals(param) ? value : null,
                seasonalOnly, membersOnly, startsOn, endsOn);
    }

    /** A seasonal flavour on sale, over those days (null: no end). Returns its id. (Ids are set here: other tests set theirs, which leaves the sequences behind.) */
    private long seasonalItem(String from, String until) {
        return jdbc.queryForObject("""
                INSERT INTO item (id, category_id, name, price, sort_order, seasonal, seasonal_from, seasonal_until)
                OVERRIDING SYSTEM VALUE
                VALUES (?, ?, ?, 10, ?, TRUE, ?::date, ?::date) RETURNING id
                """, Long.class, 900100L + ++serial, folderId, "Poster test flavour " + serial, serial, from, until);
    }

    private String connectedDisplay() throws Exception {
        String created = mockMvc.perform(post("/api/v1/displays").with(cashier())).andReturn().getResponse().getContentAsString();
        return tokenOf(JsonPath.read(created, "$.id"));
    }

    /**
     * The token of a display the POS created. (Pairing through /display/connect needs the code to be committed; these tests
     * run in a transaction that rolls back, and the pairing is covered by DisplayLifecycleIntegrationTest.)
     */
    private String tokenOf(Integer displayId) {
        return tokenService.issueDisplay(displayId.longValue(), 1L, Duration.ofHours(1)).value();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor cashier() {
        return jwt().jwt(token -> token.claim("outlet", 1L).subject(cashierId.toString()));
    }
}
