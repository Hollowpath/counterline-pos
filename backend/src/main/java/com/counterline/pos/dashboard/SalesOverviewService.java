package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.outlet.OutletService.OutletResponse;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The dashboard's charts: how sales are going over a period, across the outlets the user can see
 * (or one of them). Sales count on the day they were paid and refunds on the day they were made,
 * as in the outlet reports; net sales = sales − refunds.
 */
@Service
@Transactional(readOnly = true)
public class SalesOverviewService {

    /** Longest period a chart covers (a year, day by day). */
    static final int MAX_DAYS = 366;
    private static final int TOP_ITEMS = 8;

    private final DashboardService dashboard;
    private final NamedParameterJdbcTemplate jdbc;
    private final OutletProperties outletSettings;
    private final Clock clock;

    SalesOverviewService(DashboardService dashboard, NamedParameterJdbcTemplate jdbc, OutletProperties outletSettings,
                         Clock clock) {
        this.dashboard = dashboard;
        this.jdbc = jdbc;
        this.outletSettings = outletSettings;
        this.clock = clock;
    }

    /**
     * Headline figures for a period. {@code memberOrders}: orders with a member on them;
     * {@code itemsSold}: units charged for (free items not counted); {@code freeItems} and
     * {@code freeItemsValue}: units given free by the promotions, and what they would have cost;
     * {@code discounts}: what discounts and vouchers took off; {@code newMembers}: members who signed
     * up in the period and bought at these outlets in it.
     */
    public record Figures(BigDecimal netSales, long orders, BigDecimal averageOrder, long itemsSold, long memberOrders,
                          long newMembers, long freeItems, BigDecimal freeItemsValue, BigDecimal discounts,
                          long refundCount, BigDecimal refunds) {
    }

    public record DayTotal(LocalDate date, long orders, BigDecimal netSales) {
    }

    public record OutletTotal(Long outletId, String name, long orders, BigDecimal netSales) {
    }

    /** {@code hour}: 0–23, in the outlets' time zone. */
    public record HourTotal(int hour, long orders, BigDecimal sales) {
    }

    public record ItemTotal(String name, long quantity, BigDecimal sales) {
    }

    public record NamedTotal(String name, BigDecimal amount) {
    }

    /** A promotion's free items ({@code count}: units) or a discount's uses ({@code count}: times applied). */
    public record GivenTotal(String name, long count, BigDecimal amount) {
    }

    /**
     * {@code previous}: the same figures for the period just before, as long, for comparison.
     * {@code outletId}: the one outlet shown, or null for all the user can see.
     */
    public record Overview(LocalDate from, LocalDate to, Long outletId, Figures current, Figures previous,
                           List<DayTotal> days, List<OutletTotal> outlets, List<HourTotal> hours,
                           List<ItemTotal> topItems, List<NamedTotal> categories, List<NamedTotal> paymentMethods,
                           List<GivenTotal> promotions, List<GivenTotal> discounts, Instant generatedAt) {
    }

    public Overview overview(DashboardUser user, LocalDate from, LocalDate to, Long outletId) {
        ZoneId zone = outletSettings.timeZone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(6);
        if (start.isAfter(end)) {
            throw new BadRequestException("The period starts after it ends");
        }
        long length = ChronoUnit.DAYS.between(start, end) + 1;
        if (length > MAX_DAYS) {
            throw new BadRequestException("Choose a period of at most " + MAX_DAYS + " days");
        }

        List<OutletResponse> visible = dashboard.outlets(user);
        if (outletId != null) {
            user.requireOutlet(outletId);
            visible = visible.stream().filter(outlet -> outlet.id().equals(outletId)).toList();
        }
        List<Long> ids = visible.stream().map(OutletResponse::id).toList();
        if (ids.isEmpty()) {
            BigDecimal zero = money(BigDecimal.ZERO);
            Figures none = new Figures(zero, 0, zero, 0, 0, 0, 0, zero, zero, 0, zero);
            return new Overview(start, end, outletId, none, none, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), clock.instant());
        }

        MapSqlParameterSource params = params(ids, start, end.plusDays(1), zone);
        return new Overview(start, end, outletId,
                figures(params),
                figures(params(ids, start.minusDays(length), start, zone)),
                days(params, start, end),
                outlets(params, visible),
                hours(params),
                topItems(params),
                categories(params),
                paymentMethods(params),
                promotions(params),
                discounts(params),
                clock.instant());
    }

    private static final String PAID_IN_RANGE =
            "o.outlet_id IN (:outlets) AND o.status = 'CLOSED' AND o.closed_at >= :start AND o.closed_at < :end";
    private static final String REFUNDED_IN_RANGE =
            "o.outlet_id IN (:outlets) AND o.refunded_at >= :start AND o.refunded_at < :end";

    private Figures figures(MapSqlParameterSource params) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM sale_order o WHERE %1$s) AS orders,
                       (SELECT coalesce(sum(o.total), 0) FROM sale_order o WHERE %1$s) AS sales,
                       (SELECT count(*) FROM sale_order o WHERE %1$s AND o.customer_id IS NOT NULL) AS member_orders,
                       (SELECT coalesce(sum(l.quantity), 0) FROM sale_order o JOIN sale_order_line l ON l.order_id = o.id
                        WHERE %1$s AND l.line_total > 0) AS items,
                       (SELECT count(*) FROM customer c WHERE c.deleted_at IS NULL
                          AND c.created_at >= :start AND c.created_at < :end
                          AND EXISTS (SELECT 1 FROM sale_order o WHERE %1$s AND o.customer_id = c.id)) AS new_members,
                       (SELECT coalesce(sum(l.quantity), 0) FROM sale_order o
                          JOIN sale_order_promotion sp ON sp.order_id = o.id JOIN sale_order_line l ON l.id = sp.line_id
                        WHERE %1$s) AS free_items,
                       (SELECT coalesce(sum(sp.amount), 0) FROM sale_order o JOIN sale_order_promotion sp ON sp.order_id = o.id
                        WHERE %1$s) AS free_value,
                       (SELECT coalesce(sum(d.amount), 0) FROM sale_order o JOIN sale_order_discount d ON d.order_id = o.id
                        WHERE %1$s) AS discounts,
                       (SELECT count(*) FROM sale_order o WHERE %2$s) AS refund_count,
                       (SELECT coalesce(sum(p.amount), 0) FROM sale_order o JOIN payment p ON p.order_id = o.id
                        WHERE %2$s) AS refunds
                """.formatted(PAID_IN_RANGE, REFUNDED_IN_RANGE), params, (rs, i) -> {
                    long orders = rs.getLong("orders");
                    BigDecimal sales = rs.getBigDecimal("sales");
                    BigDecimal refunds = rs.getBigDecimal("refunds");
                    // The average of the Net sales figure beside it (after refunds), over the orders paid in the period.
                    BigDecimal net = sales.subtract(refunds);
                    BigDecimal average = orders == 0 ? BigDecimal.ZERO
                            : net.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
                    return new Figures(money(net), orders, money(average), rs.getLong("items"),
                            rs.getLong("member_orders"), rs.getLong("new_members"), rs.getLong("free_items"),
                            money(rs.getBigDecimal("free_value")), money(rs.getBigDecimal("discounts")),
                            rs.getLong("refund_count"), money(refunds));
                });
    }

    /** Every day of the period, with nothing sold shown as 0. */
    private List<DayTotal> days(MapSqlParameterSource params, LocalDate start, LocalDate end) {
        Map<LocalDate, long[]> orders = new HashMap<>();
        Map<LocalDate, BigDecimal> net = new HashMap<>();
        jdbc.query("""
                SELECT (o.closed_at AT TIME ZONE :zone)::date AS day, count(*) AS orders, sum(o.total) AS sales
                FROM sale_order o WHERE %s GROUP BY 1
                """.formatted(PAID_IN_RANGE), params, rs -> {
                    LocalDate day = rs.getObject("day", LocalDate.class);
                    orders.put(day, new long[]{rs.getLong("orders")});
                    net.merge(day, rs.getBigDecimal("sales"), BigDecimal::add);
                });
        jdbc.query("""
                SELECT (o.refunded_at AT TIME ZONE :zone)::date AS day, sum(p.amount) AS refunds
                FROM sale_order o JOIN payment p ON p.order_id = o.id WHERE %s GROUP BY 1
                """.formatted(REFUNDED_IN_RANGE), params,
                rs -> {
                    net.merge(rs.getObject("day", LocalDate.class), rs.getBigDecimal("refunds").negate(), BigDecimal::add);
                });
        List<DayTotal> days = new ArrayList<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long[] count = orders.get(day);
            days.add(new DayTotal(day, count == null ? 0 : count[0], money(net.getOrDefault(day, BigDecimal.ZERO))));
        }
        return days;
    }

    /** Every outlet shown, best first. */
    private List<OutletTotal> outlets(MapSqlParameterSource params, List<OutletResponse> visible) {
        Map<Long, long[]> orders = new HashMap<>();
        Map<Long, BigDecimal> net = new HashMap<>();
        jdbc.query("SELECT o.outlet_id, count(*) AS orders, sum(o.total) AS sales FROM sale_order o WHERE %s GROUP BY 1"
                .formatted(PAID_IN_RANGE), params, rs -> {
                    orders.put(rs.getLong("outlet_id"), new long[]{rs.getLong("orders")});
                    net.merge(rs.getLong("outlet_id"), rs.getBigDecimal("sales"), BigDecimal::add);
                });
        jdbc.query("""
                SELECT o.outlet_id, sum(p.amount) AS refunds
                FROM sale_order o JOIN payment p ON p.order_id = o.id WHERE %s GROUP BY 1
                """.formatted(REFUNDED_IN_RANGE), params,
                rs -> {
                    net.merge(rs.getLong("outlet_id"), rs.getBigDecimal("refunds").negate(), BigDecimal::add);
                });
        return visible.stream()
                .map(outlet -> {
                    long[] count = orders.get(outlet.id());
                    return new OutletTotal(outlet.id(), outlet.name(), count == null ? 0 : count[0],
                            money(net.getOrDefault(outlet.id(), BigDecimal.ZERO)));
                })
                .sorted((a, b) -> b.netSales().compareTo(a.netSales()))
                .toList();
    }

    /** Sales by the hour they were paid, 0–23 (hours with none included). */
    private List<HourTotal> hours(MapSqlParameterSource params) {
        Map<Integer, HourTotal> byHour = new HashMap<>();
        jdbc.query("""
                SELECT extract(HOUR FROM o.closed_at AT TIME ZONE :zone)::int AS hour, count(*) AS orders,
                       sum(o.total) AS sales
                FROM sale_order o WHERE %s GROUP BY 1
                """.formatted(PAID_IN_RANGE), params,
                rs -> {
                    byHour.put(rs.getInt("hour"), new HourTotal(rs.getInt("hour"), rs.getLong("orders"),
                            money(rs.getBigDecimal("sales"))));
                });
        List<HourTotal> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            hours.add(byHour.getOrDefault(hour, new HourTotal(hour, 0, money(BigDecimal.ZERO))));
        }
        return hours;
    }

    /** The items sold most (units charged for), with what they took after line discounts. */
    private List<ItemTotal> topItems(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT max(l.item_name) AS name, sum(l.quantity) AS quantity, sum(l.line_total) AS sales
                FROM sale_order o JOIN sale_order_line l ON l.order_id = o.id
                WHERE %s AND l.line_total > 0 AND l.item_id IS NOT NULL
                GROUP BY l.item_id
                ORDER BY quantity DESC, sales DESC
                LIMIT %d
                """.formatted(PAID_IN_RANGE, TOP_ITEMS), params,
                (rs, i) -> new ItemTotal(rs.getString("name"), rs.getLong("quantity"), money(rs.getBigDecimal("sales"))));
    }

    /** Sales by folder (after line discounts; free items take nothing). */
    private List<NamedTotal> categories(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT l.category_name AS name, sum(l.line_total) AS amount
                FROM sale_order o JOIN sale_order_line l ON l.order_id = o.id
                WHERE %s AND l.line_total > 0
                GROUP BY l.category_name
                ORDER BY amount DESC
                """.formatted(PAID_IN_RANGE), params,
                (rs, i) -> new NamedTotal(rs.getString("name"), money(rs.getBigDecimal("amount"))));
    }

    /** What was collected by each payment method (less refunds). */
    private List<NamedTotal> paymentMethods(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT m.name,
                       coalesce(sum(p.amount) FILTER (WHERE %1$s), 0)
                           - coalesce(sum(p.amount) FILTER (WHERE %2$s), 0) AS amount
                FROM payment p
                JOIN sale_order o ON o.id = p.order_id
                JOIN payment_method m ON m.id = p.payment_method_id
                WHERE (%1$s) OR (%2$s)
                GROUP BY m.id, m.name, m.sort_order
                ORDER BY amount DESC, m.sort_order
                """.formatted(PAID_IN_RANGE, REFUNDED_IN_RANGE), params,
                (rs, i) -> new NamedTotal(rs.getString("name"), money(rs.getBigDecimal("amount"))));
    }

    /** The free items each promotion gave (units), and what they would have cost. */
    private List<GivenTotal> promotions(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT sp.name, sum(l.quantity) AS count, sum(sp.amount) AS amount
                FROM sale_order o
                JOIN sale_order_promotion sp ON sp.order_id = o.id
                JOIN sale_order_line l ON l.id = sp.line_id
                WHERE %s
                GROUP BY sp.name
                ORDER BY count DESC, amount DESC
                """.formatted(PAID_IN_RANGE), params,
                (rs, i) -> new GivenTotal(rs.getString("name"), rs.getLong("count"), money(rs.getBigDecimal("amount"))));
    }

    /** What each discount (vouchers included) took off, and how many times it was applied. */
    private List<GivenTotal> discounts(MapSqlParameterSource params) {
        return jdbc.query("""
                SELECT d.name, count(*) AS count, sum(d.amount) AS amount
                FROM sale_order o JOIN sale_order_discount d ON d.order_id = o.id
                WHERE %s
                GROUP BY d.name
                ORDER BY amount DESC, count DESC
                """.formatted(PAID_IN_RANGE), params,
                (rs, i) -> new GivenTotal(rs.getString("name"), rs.getLong("count"), money(rs.getBigDecimal("amount"))));
    }

    /** {@code [startDay, endDay)} as instants in the outlets' time zone. */
    private static MapSqlParameterSource params(List<Long> outletIds, LocalDate startDay, LocalDate endDay, ZoneId zone) {
        return new MapSqlParameterSource()
                .addValue("outlets", outletIds)
                .addValue("start", Timestamp.from(startDay.atStartOfDay(zone).toInstant()))
                .addValue("end", Timestamp.from(endDay.atStartOfDay(zone).toInstant()))
                .addValue("zone", zone.getId());
    }

    private static BigDecimal money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }
}
