package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.outlet.OutletService.HistoricOutlet;
import com.counterline.pos.outlet.OutletService.OutletResponse;
import com.counterline.pos.reports.ReportDtos.SalesSummary;
import com.counterline.pos.reports.ReportService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The back-office dashboard's view of the chain: sales per outlet as they happen, and any outlet's
 * reports. Head office sees every outlet; a manager sees their own.
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    private static final int RECENT_ORDERS = 5;

    private final OutletService outletService;
    private final ReportService reports;
    private final JdbcTemplate jdbc;
    private final OutletProperties outletSettings;
    private final Clock clock;

    DashboardService(OutletService outletService, ReportService reports, JdbcTemplate jdbc,
                     OutletProperties outletSettings, Clock clock) {
        this.outletService = outletService;
        this.reports = reports;
        this.jdbc = jdbc;
        this.outletSettings = outletSettings;
        this.clock = clock;
    }

    public record RecentOrder(String orderNumber, Instant paidAt, BigDecimal total, String staffName, String items) {
    }

    /** One outlet's day so far. */
    public record OutletLive(Long outletId, String code, String name, boolean registerOpen, long openOrders,
                             long orderCount, BigDecimal grossSales, BigDecimal netSales, Instant lastSaleAt,
                             List<RecentOrder> recentOrders) {
    }

    public record LiveSales(LocalDate date, long orderCount, BigDecimal netSales, List<OutletLive> outlets,
                            Instant generatedAt) {
    }

    /** The open outlets this user can see (live status is only for these). */
    public List<OutletResponse> outlets(DashboardUser user) {
        return outletService.activeOutlets().stream().filter(outlet -> user.canSee(outlet.id())).toList();
    }

    /**
     * The outlets this user can see for history, closed ones too (marked {@code closed}): a closed outlet's sales stay in
     * the period's totals and its reports stay readable. Without {@code includeClosed}, only the open ones.
     */
    public List<HistoricOutlet> outlets(DashboardUser user, boolean includeClosed) {
        return outletService.allOutlets().stream()
                .filter(outlet -> user.canSee(outlet.id()) && (includeClosed || !outlet.closed()))
                .toList();
    }

    /** Sales per outlet for {@code date} (today when null), polled by the dashboard. */
    public LiveSales live(DashboardUser user, LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(outletSettings.timeZone()));
        List<OutletLive> outlets = outlets(user).stream().map(outlet -> outletLive(outlet, day)).toList();
        return new LiveSales(day, outlets.stream().mapToLong(OutletLive::orderCount).sum(),
                outlets.stream().map(OutletLive::netSales).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add),
                outlets, clock.instant());
    }

    /** Any report for an outlet this user can see, by its name in the Reports list. */
    public Object report(DashboardUser user, Long outletId, String name, LocalDate from, LocalDate to) {
        requireVisible(user, outletId);
        return switch (name) {
            case "sales-summary" -> reports.salesSummary(outletId, from, to);
            case "hourly-sales" -> reports.hourlySales(outletId, from, to);
            case "payment-methods" -> reports.paymentMethods(outletId, from, to);
            case "item-sales" -> reports.itemSales(outletId, from, to);
            case "category-sales" -> reports.categorySales(outletId, from, to);
            case "discounts" -> reports.discounts(outletId, from, to);
            case "modifiers" -> reports.modifiers(outletId, from, to);
            case "loyalty-points" -> reports.loyaltyPoints(outletId, from, to);
            case "timecards" -> reports.timecards(outletId, from, to);
            default -> throw new ResourceNotFoundException("Report " + name + " not found");
        };
    }

    private void requireVisible(DashboardUser user, Long outletId) {
        user.requireOutlet(outletId);
        outletService.findAny(outletId); // 404 for an unknown outlet; a closed one keeps its history
    }

    private OutletLive outletLive(OutletResponse outlet, LocalDate day) {
        SalesSummary summary = reports.salesSummary(outlet.id(), day, day);
        Boolean registerOpen = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM register_session WHERE outlet_id = ? AND closed_at IS NULL)",
                Boolean.class, outlet.id());
        Long openOrders = jdbc.queryForObject(
                "SELECT count(*) FROM sale_order WHERE outlet_id = ? AND status = 'OPEN'", Long.class, outlet.id());
        Instant dayStart = day.atStartOfDay(outletSettings.timeZone()).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(outletSettings.timeZone()).toInstant();
        List<RecentOrder> recent = jdbc.query("""
                SELECT 'R' || o.order_number AS number, o.closed_at, o.total, s.full_name,
                       (SELECT string_agg(l.quantity || '× ' || l.item_name, ', ' ORDER BY l.line_number)
                        FROM sale_order_line l WHERE l.order_id = o.id) AS items
                FROM sale_order o
                JOIN staff s ON s.id = o.staff_id
                WHERE o.outlet_id = ? AND o.status = 'CLOSED' AND o.closed_at >= ? AND o.closed_at < ?
                ORDER BY o.closed_at DESC, o.id DESC
                LIMIT ?
                """, (rs, i) -> new RecentOrder(rs.getString("number"),
                        rs.getObject("closed_at", OffsetDateTime.class).toInstant(), rs.getBigDecimal("total"),
                        rs.getString("full_name"), rs.getString("items")),
                outlet.id(), Timestamp.from(dayStart), Timestamp.from(dayEnd), RECENT_ORDERS);
        return new OutletLive(outlet.id(), outlet.code(), outlet.name(), Boolean.TRUE.equals(registerOpen),
                openOrders == null ? 0 : openOrders, summary.orderCount(), summary.grossSales(), summary.netSales(),
                recent.isEmpty() ? null : recent.getFirst().paidAt(), recent);
    }
}
