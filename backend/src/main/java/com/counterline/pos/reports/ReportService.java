package com.counterline.pos.reports;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.reports.ReportDtos.CategoryItemTotal;
import com.counterline.pos.reports.ReportDtos.CategorySales;
import com.counterline.pos.reports.ReportDtos.CategoryTotal;
import com.counterline.pos.reports.ReportDtos.DiningOptionTotal;
import com.counterline.pos.reports.ReportDtos.Discounts;
import com.counterline.pos.reports.ReportDtos.HourlySales;
import com.counterline.pos.reports.ReportDtos.ItemSales;
import com.counterline.pos.reports.ReportDtos.LoyaltyPoints;
import com.counterline.pos.reports.ReportDtos.Modifiers;
import com.counterline.pos.reports.ReportDtos.PaymentMethodTotal;
import com.counterline.pos.reports.ReportDtos.PaymentMethods;
import com.counterline.pos.reports.ReportDtos.PriceTotal;
import com.counterline.pos.reports.ReportDtos.SalesSummary;
import com.counterline.pos.reports.ReportDtos.StatusTotal;
import com.counterline.pos.reports.ReportDtos.Timecards;
import com.counterline.pos.reports.ReportQueries.CategoryPriceRow;
import com.counterline.pos.reports.ReportQueries.PointsMoved;
import com.counterline.pos.reports.ReportQueries.SalesTotals;
import com.counterline.pos.reports.ReportQueries.StatusCounts;
import com.counterline.pos.reports.ReportQueries.Totals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Business-day reports for one outlet. A date range [from, to] covers whole days in the outlet's time zone,
 * so "today" means the outlet's today regardless of where the server runs.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private static final long MAX_DAYS = 366;
    /** A shift open for longer than this is shown as stale: a clock-out was most likely forgotten. */
    static final Duration STALE_SHIFT = Duration.ofHours(16);

    private final ReportQueries queries;
    private final ZoneId zone;
    private final Clock clock;

    ReportService(ReportQueries queries, OutletProperties outlet, Clock clock) {
        this.queries = queries;
        this.zone = outlet.timeZone();
        this.clock = clock;
    }

    public SalesSummary salesSummary(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        return salesSummary(outletId, from, to, range.start(), range.end());
    }

    /** Also used for a register session, which spans an arbitrary time range. */
    public SalesSummary salesSummary(Long outletId, LocalDate from, LocalDate to, Instant start, Instant end) {
        SalesTotals sales = queries.sales(outletId, start, end);
        Totals refunds = queries.refunds(outletId, start, end);
        BigDecimal net = sales.gross().subtract(sales.discounts()).subtract(refunds.amount());
        List<PaymentMethodTotal> methods = queries.paymentMethods(outletId, start, end);
        return new SalesSummary(from, to, sales.count(), sales.gross(), sales.discounts(), refunds.count(),
                refunds.amount(), net, methods, totalNet(methods), orderStatuses(outletId, start, end),
                diningOptions(sales.count(), net));
    }

    /** The printed "Payment Methods" report. */
    public PaymentMethods paymentMethods(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        List<PaymentMethodTotal> methods = queries.paymentMethods(outletId, range.start(), range.end());
        return new PaymentMethods(from, to, methods, totalNet(methods));
    }

    public LoyaltyPoints loyaltyPoints(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        PointsMoved moved = queries.loyaltyPoints(outletId, range.start(), range.end());
        return new LoyaltyPoints(from, to, moved.issued(), moved.redeemed(), moved.issuedReversed(),
                moved.redeemedReturned(), queries.redemptions(outletId, range.start(), range.end()));
    }

    private static BigDecimal totalNet(List<PaymentMethodTotal> methods) {
        return methods.stream().map(PaymentMethodTotal::net).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    private List<StatusTotal> orderStatuses(Long outletId, Instant start, Instant end) {
        StatusCounts counts = queries.orderStatuses(outletId, start, end);
        return Stream.of(
                        new StatusTotal("Paid", counts.paid(), counts.paidAmount()),
                        new StatusTotal("Refunded", counts.refunded(), counts.refundedAmount()),
                        new StatusTotal("Open", counts.open(), counts.openAmount()),
                        new StatusTotal("Voided", counts.voided(), counts.voidedAmount()))
                .filter(status -> status.count() > 0)
                .toList();
    }

    /** Every sale is Retail with 1 pax ("Retail · Pax: 1" on the receipt). */
    private static List<DiningOptionTotal> diningOptions(long orders, BigDecimal net) {
        if (orders == 0) {
            return List.of();
        }
        return List.of(new DiningOptionTotal("Retail", orders, net, orders,
                net.divide(BigDecimal.valueOf(orders), 2, RoundingMode.DOWN)));
    }

    public HourlySales hourlySales(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        return new HourlySales(from, to, queries.hourly(outletId, range.start(), range.end(), zone));
    }

    public ItemSales itemSales(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        return new ItemSales(from, to, queries.items(outletId, range.start(), range.end()));
    }

    /**
     * As printed: categories, then their items, then each item by unit price ("Unit Price", or
     * "Unit Value" for a free promotional item, "Sample" for a free sample), largest quantity first.
     */
    public CategorySales categorySales(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        // category → item key → that item's rows, one per unit price
        Map<String, Map<String, List<CategoryPriceRow>>> rows = new LinkedHashMap<>();
        for (CategoryPriceRow row : queries.categoryPrices(outletId, range.start(), range.end())) {
            rows.computeIfAbsent(String.valueOf(row.categoryName()), name -> new LinkedHashMap<>())
                    .computeIfAbsent(row.itemKey(), key -> new ArrayList<>()).add(row);
        }
        List<CategoryTotal> categories = rows.values().stream()
                .map(items -> {
                    List<CategoryItemTotal> itemTotals = items.values().stream()
                            .map(ReportService::itemTotal)
                            .sorted(LARGEST_ITEM_FIRST)
                            .toList();
                    String categoryName = items.values().iterator().next().getFirst().categoryName();
                    return new CategoryTotal(categoryName,
                            itemTotals.stream().mapToLong(CategoryItemTotal::quantity).sum(),
                            sum(itemTotals.stream().map(CategoryItemTotal::amount)), itemTotals);
                })
                .sorted(Comparator.comparingLong(CategoryTotal::quantity).reversed()
                        .thenComparing(CategoryTotal::categoryName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new CategorySales(from, to, categories);
    }

    private static final Comparator<CategoryItemTotal> LARGEST_ITEM_FIRST =
            Comparator.comparingLong(CategoryItemTotal::quantity).reversed().thenComparing(CategoryItemTotal::itemName);

    private static CategoryItemTotal itemTotal(List<CategoryPriceRow> rows) {
        List<PriceTotal> prices = rows.stream()
                .map(row -> new PriceTotal(priceLabel(row), row.unitPrice(), row.quantity(), row.amount()))
                .sorted(Comparator.comparingLong(PriceTotal::quantity).reversed()
                        .thenComparing(PriceTotal::unitPrice, Comparator.reverseOrder()))
                .toList();
        return new CategoryItemTotal(rows.getFirst().itemName(), prices.stream().mapToLong(PriceTotal::quantity).sum(),
                sum(prices.stream().map(PriceTotal::amount)), prices);
    }

    private static String priceLabel(CategoryPriceRow row) {
        if (row.promotional()) {
            return "Unit Value";
        }
        return row.unitPrice().signum() == 0 ? "Sample" : "Unit Price";
    }

    private static BigDecimal sum(Stream<BigDecimal> amounts) {
        return amounts.reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    public Discounts discounts(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        return new Discounts(from, to, queries.discounts(outletId, range.start(), range.end()));
    }

    public Modifiers modifiers(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        return new Modifiers(from, to, queries.modifiers(outletId, range.start(), range.end()));
    }

    public Timecards timecards(Long outletId, LocalDate from, LocalDate to) {
        Range range = range(from, to);
        Instant now = clock.instant();
        return new Timecards(from, to, queries.timecards(outletId, range.start(), range.end()),
                queries.openShifts(outletId, since -> Duration.between(since, now).compareTo(STALE_SHIFT) > 0));
    }

    private record Range(Instant start, Instant end) {
    }

    private Range range(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new BadRequestException("'to' must not be before 'from'");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new BadRequestException("A report can cover at most " + MAX_DAYS + " days");
        }
        return new Range(from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
