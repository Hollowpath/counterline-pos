package com.counterline.pos.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Report bodies, laid out like printed end-of-day reports. Conventions: a sale counts on the
 * day it was paid (closed); a refund on the day it was refunded. Gross is before discounts and
 * includes orders refunded later; net = gross − discounts − refunds. Item and category amounts
 * are before discounts (a free promotional item counts at its value).
 */
public final class ReportDtos {

    private ReportDtos() {
    }

    /**
     * netSales = grossSales − discounts − refunds; {@code totalCollected} = what the payment
     * methods took, less refunds (equal to net sales).
     */
    public record SalesSummary(LocalDate from, LocalDate to, long orderCount, BigDecimal grossSales,
                               BigDecimal discounts, long refundCount, BigDecimal refunds, BigDecimal netSales,
                               List<PaymentMethodTotal> paymentMethods, BigDecimal totalCollected,
                               List<StatusTotal> orderStatuses, List<DiningOptionTotal> diningOptions) {
    }

    /**
     * Orders by status in the period: Paid (paid in it), Refunded (refunded in it), Open (placed in
     * it, still unpaid) and Voided (voided in it). Only statuses with orders are listed.
     */
    public record StatusTotal(String status, long count, BigDecimal amount) {
    }

    /**
     * Every sale is "Retail" with 1 pax, as printed on receipts. {@code perPaxAverage} =
     * amount ÷ pax, cut to the sen as the printed report does (RM784.00 ÷ 23 = RM34.08).
     */
    public record DiningOptionTotal(String name, long orderCount, BigDecimal amount, long paxCount,
                                    BigDecimal perPaxAverage) {
    }

    /** The printed "Payment Methods" report: per method, payments and amount less refunds. */
    public record PaymentMethods(LocalDate from, LocalDate to, List<PaymentMethodTotal> methods, BigDecimal total) {
    }

    public record PaymentMethodTotal(String method, long paymentCount, BigDecimal collected,
                                     BigDecimal refunded, BigDecimal net) {
    }

    public record HourlySales(LocalDate from, LocalDate to, List<HourTotal> hours) {
    }

    /**
     * {@code hour} is 0–23 in the outlet's time zone. {@code sales} are orders paid in that hour
     * (after discounts), {@code refunds} those refunded in it, and {@code net} = sales − refunds,
     * so the hours add up to the Sales Summary's net sales.
     */
    public record HourTotal(int hour, long orderCount, BigDecimal sales, BigDecimal refunds, BigDecimal net) {
    }

    public record ItemSales(LocalDate from, LocalDate to, List<ItemTotal> items) {
    }

    public record ItemTotal(String itemName, long quantity, BigDecimal amount) {
    }

    public record CategorySales(LocalDate from, LocalDate to, List<CategoryTotal> categories) {
    }

    /** Largest quantity first, as printed; items likewise. */
    public record CategoryTotal(String categoryName, long quantity, BigDecimal amount, List<CategoryItemTotal> items) {
    }

    /** {@code prices}: the item's sales by unit price ("5 Unit Price RM50.00"). */
    public record CategoryItemTotal(String itemName, long quantity, BigDecimal amount, List<PriceTotal> prices) {
    }

    /**
     * {@code label}: "Unit Price" for an item sold, "Unit Value" for a free promotional item (counted
     * at its value), "Sample" for a free sample.
     */
    public record PriceTotal(String label, BigDecimal unitPrice, long quantity, BigDecimal amount) {
    }

    /**
     * The printed "Loyalty Points" report: points issued and redeemed in the period (redeemed by
     * promotion, e.g. Member Redemption), and what refunds took back or gave back.
     */
    public record LoyaltyPoints(LocalDate from, LocalDate to, long issued, long redeemed, long issuedReversed,
                                long redeemedReturned, List<PointsTotal> redemptions) {
    }

    /** {@code count}: items redeemed. */
    public record PointsTotal(String name, long count, long points) {
    }

    public record Discounts(LocalDate from, LocalDate to, List<NamedTotal> discounts) {
    }

    /** {@code count}: how many times it was applied. */
    public record NamedTotal(String name, long count, BigDecimal amount) {
    }

    public record Modifiers(LocalDate from, LocalDate to, List<ModifierTotal> options) {
    }

    public record ModifierTotal(String groupName, String optionName, long quantity, BigDecimal amount) {
    }

    /**
     * {@code entries}: the clock events in the period. {@code openShifts}: everyone clocked in at the outlet now,
     * however long ago they clocked in (a shift left open stays listed until it is closed).
     */
    public record Timecards(LocalDate from, LocalDate to, List<TimecardEntry> entries, List<OpenShift> openShifts) {
    }

    /** {@code correctedBy}/{@code correctedReason}: set on an OUT that head office or a manager entered for the person. */
    public record TimecardEntry(String staffName, String type, Instant at, String correctedBy, String correctedReason,
                                Instant correctedAt) {
    }

    /** {@code stale}: open for longer than a working shift can be, so most likely a clock-out was forgotten. */
    public record OpenShift(Long staffId, String staffName, Instant since, boolean stale) {
    }
}
