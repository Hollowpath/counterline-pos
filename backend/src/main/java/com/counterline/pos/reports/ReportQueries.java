package com.counterline.pos.reports;

import com.counterline.pos.reports.ReportDtos.HourTotal;
import com.counterline.pos.reports.ReportDtos.ItemTotal;
import com.counterline.pos.reports.ReportDtos.ModifierTotal;
import com.counterline.pos.reports.ReportDtos.NamedTotal;
import com.counterline.pos.reports.ReportDtos.PaymentMethodTotal;
import com.counterline.pos.reports.ReportDtos.PointsTotal;
import com.counterline.pos.reports.ReportDtos.TimecardEntry;
import com.counterline.pos.reports.ReportDtos.OpenShift;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reporting SQL for one outlet. Every query takes the outlet and a half-open time range
 * [start, end). Sales are filtered on closed_at (partial index sale_order_outlet_closed_at_idx),
 * refunds on refunded_at.
 */
@Repository
class ReportQueries {

    private static final String CLOSED_IN_RANGE =
            "o.outlet_id = :outlet AND o.status = 'CLOSED' AND o.closed_at >= :start AND o.closed_at < :end";
    private static final String REFUNDED_IN_RANGE =
            "o.outlet_id = :outlet AND o.refunded_at >= :start AND o.refunded_at < :end";

    private final NamedParameterJdbcTemplate jdbc;

    ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Totals(long count, BigDecimal amount) {
    }

    record SalesTotals(long count, BigDecimal gross, BigDecimal discounts) {
    }

    /** Gross = before any discount (sum of subtotals); discounts = line + order discounts. */
    SalesTotals sales(Long outletId, Instant start, Instant end) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, coalesce(sum(o.subtotal), 0) AS gross,
                       coalesce(sum(o.discount_total), 0) AS discounts
                FROM sale_order o
                WHERE %s
                """.formatted(CLOSED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> new SalesTotals(rs.getLong("n"), rs.getBigDecimal("gross"), rs.getBigDecimal("discounts")));
    }

    /**
     * Refunds are full, so the refunded amount is everything paid on the refunded orders. A zero-total
     * sale (no payments) is still a refund, of nothing.
     */
    Totals refunds(Long outletId, Instant start, Instant end) {
        return jdbc.queryForObject("""
                SELECT count(*) AS n,
                       coalesce(sum((SELECT coalesce(sum(p.amount), 0) FROM payment p WHERE p.order_id = o.id)), 0) AS amount
                FROM sale_order o
                WHERE %s
                """.formatted(REFUNDED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> new Totals(rs.getLong("n"), rs.getBigDecimal("amount")));
    }

    List<PaymentMethodTotal> paymentMethods(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT m.name,
                       count(p.id) FILTER (WHERE %1$s) AS payment_count,
                       coalesce(sum(p.amount) FILTER (WHERE %1$s), 0) AS collected,
                       coalesce(sum(p.amount) FILTER (WHERE %2$s), 0) AS refunded
                FROM payment p
                JOIN sale_order o ON o.id = p.order_id
                JOIN payment_method m ON m.id = p.payment_method_id
                WHERE (%1$s) OR (%2$s)
                GROUP BY m.id, m.name, m.sort_order
                ORDER BY m.sort_order
                """.formatted(CLOSED_IN_RANGE, REFUNDED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> {
                    BigDecimal collected = rs.getBigDecimal("collected");
                    BigDecimal refunded = rs.getBigDecimal("refunded");
                    return new PaymentMethodTotal(rs.getString("name"), rs.getLong("payment_count"),
                            collected, refunded, collected.subtract(refunded));
                });
    }

    /**
     * Sales by hour of the day in the outlet's time zone, less refunds in the hour they were
     * made (refunds are full, so a refund is the order's total).
     */
    List<HourTotal> hourly(Long outletId, Instant start, Instant end, ZoneId zone) {
        return jdbc.query("""
                SELECT hour, sum(n) AS n, sum(sales) AS sales, sum(refunds) AS refunds
                FROM (
                    SELECT extract(HOUR FROM o.closed_at AT TIME ZONE :zone)::int AS hour,
                           1 AS n, o.total AS sales, 0 AS refunds
                    FROM sale_order o
                    WHERE %s
                    UNION ALL
                    SELECT extract(HOUR FROM o.refunded_at AT TIME ZONE :zone)::int,
                           0, 0, o.total
                    FROM sale_order o
                    WHERE %s
                ) AS by_hour
                GROUP BY hour
                ORDER BY hour
                """.formatted(CLOSED_IN_RANGE, REFUNDED_IN_RANGE), range(outletId, start, end).addValue("zone", zone.getId()),
                (rs, i) -> {
                    BigDecimal sales = rs.getBigDecimal("sales");
                    BigDecimal refunds = rs.getBigDecimal("refunds");
                    return new HourTotal(rs.getInt("hour"), rs.getLong("n"), sales, refunds, sales.subtract(refunds));
                });
    }

    List<ItemTotal> items(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT max(l.item_name) AS item_name, sum(l.quantity) AS quantity, sum(l.line_subtotal) AS amount
                FROM sale_order_line l
                JOIN sale_order o ON o.id = l.order_id
                WHERE %s
                GROUP BY l.item_id
                ORDER BY amount DESC, item_name
                """.formatted(CLOSED_IN_RANGE), range(outletId, start, end), ReportQueries::itemTotal);
    }

    /** Orders by status in the period; see {@link ReportDtos.StatusTotal}. */
    record StatusCounts(long paid, BigDecimal paidAmount, long refunded, BigDecimal refundedAmount,
                        long open, BigDecimal openAmount, long voided, BigDecimal voidedAmount) {
    }

    StatusCounts orderStatuses(Long outletId, Instant start, Instant end) {
        String refunded = REFUNDED_IN_RANGE;
        String open = "o.outlet_id = :outlet AND o.status = 'OPEN' AND o.created_at >= :start AND o.created_at < :end";
        String voided = "o.outlet_id = :outlet AND o.status = 'VOIDED' AND o.voided_at >= :start AND o.voided_at < :end";
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE %1$s) AS paid, coalesce(sum(o.total) FILTER (WHERE %1$s), 0) AS paid_amount,
                       count(*) FILTER (WHERE %2$s) AS refunded, coalesce(sum(o.total) FILTER (WHERE %2$s), 0) AS refunded_amount,
                       count(*) FILTER (WHERE %3$s) AS open, coalesce(sum(o.total) FILTER (WHERE %3$s), 0) AS open_amount,
                       count(*) FILTER (WHERE %4$s) AS voided, coalesce(sum(o.total) FILTER (WHERE %4$s), 0) AS voided_amount
                FROM sale_order o
                WHERE (%1$s) OR (%2$s) OR (%3$s) OR (%4$s)
                """.formatted(CLOSED_IN_RANGE, refunded, open, voided), range(outletId, start, end),
                (rs, i) -> new StatusCounts(rs.getLong("paid"), rs.getBigDecimal("paid_amount"),
                        rs.getLong("refunded"), rs.getBigDecimal("refunded_amount"),
                        rs.getLong("open"), rs.getBigDecimal("open_amount"),
                        rs.getLong("voided"), rs.getBigDecimal("voided_amount")));
    }

    /**
     * One row per category, item and unit price (with options), split promotional / not; the
     * service groups them. {@code itemKey} tells custom amounts (no item) apart from items.
     */
    record CategoryPriceRow(String categoryName, String itemKey, String itemName, BigDecimal unitPrice,
                            boolean promotional, long quantity, BigDecimal amount) {
    }

    List<CategoryPriceRow> categoryPrices(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT l.category_name, coalesce('i' || l.item_id, 'c') AS item_key, max(l.item_name) AS item_name,
                       l.unit_price + l.modifier_unit_price AS unit_price, (p.id IS NOT NULL) AS promotional,
                       sum(l.quantity) AS quantity, sum(l.line_subtotal) AS amount
                FROM sale_order_line l
                JOIN sale_order o ON o.id = l.order_id
                LEFT JOIN sale_order_promotion p ON p.line_id = l.id
                WHERE %s
                GROUP BY l.category_name, l.item_id, l.unit_price + l.modifier_unit_price, (p.id IS NOT NULL)
                """.formatted(CLOSED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> new CategoryPriceRow(rs.getString("category_name"), rs.getString("item_key"),
                        rs.getString("item_name"), rs.getBigDecimal("unit_price"), rs.getBoolean("promotional"),
                        rs.getLong("quantity"), rs.getBigDecimal("amount")));
    }

    record PointsMoved(long issued, long redeemed, long issuedReversed, long redeemedReturned) {
    }

    /** Points moved in the period, from the loyalty ledger (all positive numbers). */
    PointsMoved loyaltyPoints(Long outletId, Instant start, Instant end) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(e.points) FILTER (WHERE e.type = 'EARN'), 0) AS issued,
                       coalesce(-sum(e.points) FILTER (WHERE e.type = 'REDEEM'), 0) AS redeemed,
                       coalesce(-sum(e.points) FILTER (WHERE e.type = 'REVERSE_EARN'), 0) AS issued_reversed,
                       coalesce(sum(e.points) FILTER (WHERE e.type = 'REVERSE_REDEEM'), 0) AS redeemed_returned
                FROM loyalty_entry e
                JOIN sale_order o ON o.id = e.order_id
                WHERE o.outlet_id = :outlet AND e.created_at >= :start AND e.created_at < :end
                """, range(outletId, start, end),
                (rs, i) -> new PointsMoved(rs.getLong("issued"), rs.getLong("redeemed"),
                        rs.getLong("issued_reversed"), rs.getLong("redeemed_returned")));
    }

    /** Points redeemed in the period by promotion: the redeeming orders' points-costing lines. */
    List<PointsTotal> redemptions(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT max(p.name) AS name, sum(l.quantity) AS n, sum(p.points) AS points
                FROM loyalty_entry e
                JOIN sale_order_promotion p ON p.order_id = e.order_id AND p.points > 0
                JOIN sale_order_line l ON l.id = p.line_id
                JOIN sale_order o ON o.id = e.order_id
                WHERE o.outlet_id = :outlet AND e.type = 'REDEEM' AND e.created_at >= :start AND e.created_at < :end
                GROUP BY p.promotion_id
                ORDER BY points DESC, name
                """, range(outletId, start, end),
                (rs, i) -> new PointsTotal(rs.getString("name"), rs.getLong("n"), rs.getLong("points")));
    }

    /** Amount taken off per discount (line and order level) on sales in the range. */
    List<NamedTotal> discounts(Long outletId, Instant start, Instant end) {
        // Promotions (free promotional items) are listed with the discounts, as both are
        // "Discounts & Comps" on the Sales Summary.
        return jdbc.query("""
                SELECT max(name) AS name, count(*) AS n, sum(amount) AS amount
                FROM (
                    SELECT 'D' || d.discount_id AS source, d.name, d.amount
                    FROM sale_order_discount d
                    JOIN sale_order o ON o.id = d.order_id
                    WHERE %1$s
                    UNION ALL
                    SELECT 'P' || p.promotion_id, p.name, p.amount
                    FROM sale_order_promotion p
                    JOIN sale_order o ON o.id = p.order_id
                    WHERE %1$s
                ) AS deductions
                GROUP BY source
                ORDER BY amount DESC, name
                """.formatted(CLOSED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> new NamedTotal(rs.getString("name"), rs.getLong("n"), rs.getBigDecimal("amount")));
    }

    /** How often each option was picked: per-unit picks times line quantity. */
    List<ModifierTotal> modifiers(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT max(m.group_name) AS group_name, max(m.option_name) AS option_name,
                       sum(m.quantity * l.quantity) AS quantity,
                       sum(m.unit_price * m.quantity * l.quantity) AS amount
                FROM sale_order_line_modifier m
                JOIN sale_order_line l ON l.id = m.line_id
                JOIN sale_order o ON o.id = l.order_id
                WHERE %s
                GROUP BY m.option_id
                ORDER BY group_name, quantity DESC, option_name
                """.formatted(CLOSED_IN_RANGE), range(outletId, start, end),
                (rs, i) -> new ModifierTotal(rs.getString("group_name"), rs.getString("option_name"),
                        rs.getLong("quantity"), rs.getBigDecimal("amount")));
    }

    List<TimecardEntry> timecards(Long outletId, Instant start, Instant end) {
        return jdbc.query("""
                SELECT s.full_name, t.type, t.occurred_at, t.corrected_by, t.corrected_reason, t.corrected_at
                FROM timecard_event t
                JOIN staff s ON s.id = t.staff_id
                WHERE t.outlet_id = :outlet AND t.occurred_at >= :start AND t.occurred_at < :end
                ORDER BY t.occurred_at, t.id
                """, range(outletId, start, end),
                (rs, i) -> new TimecardEntry(rs.getString("full_name"), rs.getString("type"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(), rs.getString("corrected_by"),
                        rs.getString("corrected_reason"),
                        rs.getObject("corrected_at", OffsetDateTime.class) == null ? null
                                : rs.getObject("corrected_at", OffsetDateTime.class).toInstant()));
    }

    /** Shifts open now at the outlet: an IN that is its staff member's latest event. */
    List<OpenShift> openShifts(Long outletId, java.util.function.Predicate<Instant> stale) {
        return jdbc.query("""
                SELECT s.id, s.full_name, e.occurred_at
                FROM timecard_event e
                JOIN staff s ON s.id = e.staff_id
                WHERE e.outlet_id = :outlet AND e.type = 'IN'
                  AND NOT EXISTS (SELECT 1 FROM timecard_event n WHERE n.staff_id = e.staff_id
                                  AND (n.occurred_at > e.occurred_at OR (n.occurred_at = e.occurred_at AND n.id > e.id)))
                ORDER BY e.occurred_at, e.id
                """, new MapSqlParameterSource("outlet", outletId),
                (rs, i) -> {
                    Instant since = rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
                    return new OpenShift(rs.getLong("id"), rs.getString("full_name"), since, stale.test(since));
                });
    }

    private static ItemTotal itemTotal(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ItemTotal(rs.getString("item_name"), rs.getLong("quantity"), rs.getBigDecimal("amount"));
    }

    private static MapSqlParameterSource range(Long outletId, Instant start, Instant end) {
        return new MapSqlParameterSource()
                .addValue("outlet", outletId)
                .addValue("start", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
                .addValue("end", OffsetDateTime.ofInstant(end, ZoneOffset.UTC));
    }
}
