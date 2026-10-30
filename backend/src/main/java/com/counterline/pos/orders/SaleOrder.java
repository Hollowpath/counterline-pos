package com.counterline.pos.orders;

import com.counterline.pos.catalog.Item;
import com.counterline.pos.catalog.ModifierGroup;
import com.counterline.pos.discounts.Discount;
import com.counterline.pos.discounts.DiscountCalculator;
import com.counterline.pos.orders.ModifierSelection.Chosen;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.staff.Staff;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sale and its lifecycle:
 * <pre>
 *   OPEN ──pay (exact total)──▶ CLOSED ──refund (full, manager)──▶ CLOSED + refunded
 *   OPEN ──void (reason, manager)──▶ VOIDED
 * </pre>
 * Voiding applies to unpaid orders; a paid order is refunded instead. Payments must equal the
 * total exactly (change for cash is recorded on the cash payment), using at most one cashless
 * method plus cash.
 */
@Entity
@Table(name = "sale_order")
public class SaleOrder {

    private static final int MAX_DISCOUNTS_PER_SALE = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private long version;

    /** The outlet where the sale was made. */
    private Long outletId;

    private long orderNumber;

    private String receiptCode;

    private UUID clientRequestId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private String note;

    /** Sum of line subtotals, before any discount (gross). */
    private BigDecimal subtotal;

    /** Line discounts plus order discounts. */
    private BigDecimal discountTotal;

    /** What is due: subtotal − discountTotal. */
    private BigDecimal total;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private Staff staff;

    private Instant createdAt;

    private Instant closedAt;

    /**
     * The instant the promotions' time rules are read at: their dates, the member's birth month, the registration
     * reward's 24 hours. It is when the order was placed (or last edited), so paying later does not change what the
     * order was eligible for; only the member's points and the once-only rewards are looked at again at payment.
     */
    private Instant eligibilityAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private UUID paymentRequestId;

    private String voidReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voided_by")
    private Staff voidedBy;

    private Instant voidedAt;

    private String refundReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refunded_by")
    private Staff refundedBy;

    private Instant refundedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNumber")
    private List<SaleOrderLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<Payment> payments = new ArrayList<>();

    /** Line and order discounts as applied (line discounts reference their line). */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderDiscount> discounts = new ArrayList<>();

    /** Promotional lines made free, one per line. */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<SaleOrderPromotion> promotions = new ArrayList<>();

    /** The member's points around this sale; set when a member's sale is paid. */
    private Integer loyaltyPointsBefore;

    private Integer loyaltyPointsEarned;

    private Integer loyaltyPointsRedeemed;

    protected SaleOrder() {
    }

    public SaleOrder(Long outletId, long orderNumber, String receiptCode, UUID clientRequestId, String note,
                     Staff staff, Instant createdAt) {
        this.outletId = outletId;
        this.orderNumber = orderNumber;
        this.receiptCode = receiptCode;
        this.clientRequestId = clientRequestId;
        this.note = note;
        this.staff = staff;
        this.createdAt = createdAt;
        this.eligibilityAt = createdAt;
        this.status = OrderStatus.OPEN;
        this.subtotal = BigDecimal.ZERO.setScale(2);
        this.discountTotal = BigDecimal.ZERO.setScale(2);
        this.total = BigDecimal.ZERO.setScale(2);
    }

    /** The customer the sale is made to (optional). */
    public void assignCustomer(Customer customer) {
        requireStatus(OrderStatus.OPEN);
        this.customer = customer;
    }

    /**
     * "Edit Order", step 1: takes off the discounts and promotions of a placed order, to be applied
     * again. Flushed before {@link #clearForEdit(String)}, as they refer to the lines.
     */
    public void removeDeductions() {
        requireStatus(OrderStatus.OPEN);
        if (!payments.isEmpty()) {
            throw new IllegalStateException("A placed order has no payments");
        }
        discounts.clear();
        promotions.clear();
    }

    /**
     * "Edit Order", step 2: empties the placed order so it can be filled again from the cart. It
     * keeps its number, receipt code and when it was placed.
     */
    public void clearForEdit(String note) {
        requireStatus(OrderStatus.OPEN);
        if (!discounts.isEmpty() || !promotions.isEmpty()) {
            throw new IllegalStateException("Remove the deductions first");
        }
        lines.clear();
        customer = null;
        this.note = note;
        subtotal = BigDecimal.ZERO.setScale(2);
        discountTotal = BigDecimal.ZERO.setScale(2);
        total = BigDecimal.ZERO.setScale(2);
    }

    /** A simple item: no options, note or discounts. */
    public SaleOrderLine addLine(Item item, int quantity) {
        return addItemLine(item, List.of(), Map.of(), quantity, null, List.of());
    }

    /**
     * An item line. {@code picks} (option id → times picked, per unit) must satisfy every one of
     * the item's option groups exactly; {@code lineDiscounts} must be in display order.
     */
    public SaleOrderLine addItemLine(Item item, List<ModifierGroup> groups, Map<Long, Integer> picks, int quantity,
                                     String note, List<Discount> lineDiscounts) {
        List<Chosen> chosen = ModifierSelection.validate(item.getName(), groups, picks);
        return add(SaleOrderLine.forItem(this, lines.size() + 1, item, chosen, quantity, note), lineDiscounts);
    }

    /** "Custom amount" from the keypad: a line with a price but no catalogue item. */
    public SaleOrderLine addCustomAmountLine(BigDecimal amount, int quantity, String note, List<Discount> lineDiscounts) {
        return add(SaleOrderLine.forCustomAmount(this, lines.size() + 1, amount, quantity, note), lineDiscounts);
    }

    /**
     * A promotional item's line is deducted in full by its promotion; whether the sale qualifies
     * is checked once the whole sale is known ({@link PromotionRules}).
     */
    private SaleOrderLine add(SaleOrderLine line, List<Discount> lineDiscounts) {
        requireStatus(OrderStatus.OPEN);
        if (discounts.stream().anyMatch(discount -> discount.getLine() == null)) {
            throw new IllegalStateException("Order discounts are applied after all lines");
        }
        lines.add(line);
        BigDecimal lineDiscountTotal;
        Promotion promotion = line.getItem() == null ? null : line.getItem().getPromotion();
        if (promotion != null) {
            if (!lineDiscounts.isEmpty()) {
                throw new BusinessRuleException(line.getItemName() + " is promotional and cannot take a discount");
            }
            SaleOrderPromotion applied = new SaleOrderPromotion(this, line, promotion);
            promotions.add(applied);
            lineDiscountTotal = applied.getAmount();
        } else {
            lineDiscountTotal = recordDiscounts(line, line.getLineSubtotal(), lineDiscounts);
        }
        line.setDiscountTotal(lineDiscountTotal);
        subtotal = subtotal.add(line.getLineSubtotal());
        discountTotal = discountTotal.add(lineDiscountTotal);
        total = subtotal.subtract(discountTotal);
        return line;
    }

    /** Discounts on the whole order (the % tab), taken off the total after line discounts. */
    public void applyOrderDiscounts(List<Discount> orderDiscounts) {
        requireStatus(OrderStatus.OPEN);
        BigDecimal taken = recordDiscounts(null, total, orderDiscounts);
        discountTotal = discountTotal.add(taken);
        total = subtotal.subtract(discountTotal);
    }

    /** Whether a discount on the sale needs a manager's approval (a department comp). */
    public boolean needsDiscountApproval() {
        return discounts.stream().anyMatch(discount -> discount.getDiscount().isRequiresApproval());
    }

    /** Records the manager who approved the discounts that need approval. */
    public void approveDiscounts(Staff manager) {
        requireStatus(OrderStatus.OPEN);
        discounts.stream()
                .filter(discount -> discount.getDiscount().isRequiresApproval())
                .forEach(discount -> discount.approve(manager));
    }

    /** At most one discount per sale, whether on a line or on the whole order (outlet rule). */
    private BigDecimal recordDiscounts(SaleOrderLine line, BigDecimal base, List<Discount> toApply) {
        if (discounts.size() + toApply.size() > MAX_DISCOUNTS_PER_SALE) {
            throw new BusinessRuleException("1 discount per sale");
        }
        List<BigDecimal> amounts = DiscountCalculator.amounts(base, toApply);
        for (int i = 0; i < toApply.size(); i++) {
            discounts.add(new SaleOrderDiscount(this, line, toApply.get(i), amounts.get(i)));
        }
        return amounts.stream().reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /**
     * A cashless payment needs its terminal reference; a cash payment may record what was handed
     * over ({@code cashTendered}, at least the amount). One cashless method and/or cash per sale.
     */
    public void addPayment(PaymentMethod method, BigDecimal amount, String reference, BigDecimal cashTendered,
                           Staff takenBy, Instant at) {
        requireStatus(OrderStatus.OPEN);
        if (method.isCash()) {
            if (cashTendered != null && cashTendered.compareTo(amount) < 0) {
                throw new BusinessRuleException("Cash received (RM" + cashTendered + ") is less than the amount (RM" + amount + ")");
            }
        } else {
            if (reference == null || reference.isBlank()) {
                throw new BusinessRuleException(method.getName() + " reference needed");
            }
            if (cashTendered != null) {
                throw new BusinessRuleException("Cash received only applies to cash payments");
            }
        }
        if (payments.stream().anyMatch(existing -> existing.getPaymentMethod().getKind() == method.getKind())) {
            throw new BusinessRuleException(method.isCash()
                    ? "A sale takes one cash payment"
                    : "A sale takes one cashless payment; the rest can be paid in cash");
        }
        payments.add(new Payment(this, method, amount, method.isCash() ? null : reference, cashTendered, takenBy, at));
    }

    /** Points the promotional lines cost the member (Member Redemption). */
    public int getPointsToRedeem() {
        return promotions.stream().mapToInt(SaleOrderPromotion::getPoints).sum();
    }

    /** The receipt's LOYALTY PROGRAM block, recorded when a member's sale is paid. */
    public void recordLoyalty(int pointsBefore, int earned, int redeemed) {
        if (customer == null) {
            throw new IllegalStateException("Loyalty points need a member on the sale");
        }
        this.loyaltyPointsBefore = pointsBefore;
        this.loyaltyPointsEarned = earned;
        this.loyaltyPointsRedeemed = redeemed;
    }

    public BigDecimal getAmountPaid() {
        return payments.stream().map(Payment::getAmount).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }

    /** "Place Order": keeps the order OPEN and unpaid, to be paid later from Past Orders. */
    public void place() {
        requireStatus(OrderStatus.OPEN);
        requireLines();
        if (!payments.isEmpty()) {
            throw new BusinessRuleException("Payments are not taken when placing an order");
        }
    }

    /**
     * Completes the sale. A zero-total sale (free items) needs no payment.
     * {@code at} is when it was paid, which decides the day it is reported on.
     */
    public void close(Instant at) {
        requireStatus(OrderStatus.OPEN);
        requireLines();
        if (getAmountPaid().compareTo(total) != 0) {
            throw new BusinessRuleException(
                    "Payments (RM" + getAmountPaid() + ") must equal the order total (RM" + total + ")");
        }
        status = OrderStatus.CLOSED;
        closedAt = at;
    }

    /** Closes a previously placed order; {@code requestId} makes a retried payment idempotent. */
    public void closeWithPaymentRequest(UUID requestId, Instant at) {
        close(at);
        this.paymentRequestId = requestId;
    }

    public void voidOrder(String reason, Staff manager, Instant at) {
        if (status != OrderStatus.OPEN) {
            throw new BusinessRuleException(status == OrderStatus.CLOSED
                    ? "Order " + getDisplayNumber() + " has been paid; issue a refund instead"
                    : "Order " + getDisplayNumber() + " is already voided");
        }
        this.status = OrderStatus.VOIDED;
        this.voidReason = reason;
        this.voidedBy = manager;
        this.voidedAt = at;
    }

    /** Full refund to the original payment methods. */
    public void refund(String reason, Staff manager, Instant at) {
        if (status != OrderStatus.CLOSED) {
            throw new BusinessRuleException("Only a paid order can be refunded");
        }
        if (isRefunded()) {
            throw new BusinessRuleException("Order " + getDisplayNumber() + " has already been refunded");
        }
        // A zero-total sale (free items, or a voucher that covered it) has no money to return, but is
        // still refunded once: that is what gives back its voucher and points.
        this.refundReason = reason;
        this.refundedBy = manager;
        this.refundedAt = at;
    }

    private void requireLines() {
        if (lines.isEmpty()) {
            throw new BusinessRuleException("A sale needs at least one item");
        }
    }

    private void requireStatus(OrderStatus required) {
        if (status != required) {
            throw new BusinessRuleException("Order " + getDisplayNumber() + " is " + status.name().toLowerCase());
        }
    }

    public Long getOutletId() {
        return outletId;
    }

    public Long getId() {
        return id;
    }

    /** As shown in the POS, e.g. "R355". */
    public String getDisplayNumber() {
        return "R" + orderNumber;
    }

    public String getReceiptCode() {
        return receiptCode;
    }

    public UUID getClientRequestId() {
        return clientRequestId;
    }

    public UUID getPaymentRequestId() {
        return paymentRequestId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDiscountTotal() {
        return discountTotal;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public List<SaleOrderDiscount> getDiscounts() {
        return Collections.unmodifiableList(discounts);
    }

    public List<SaleOrderPromotion> getPromotions() {
        return Collections.unmodifiableList(promotions);
    }

    public Integer getLoyaltyPointsBefore() {
        return loyaltyPointsBefore;
    }

    public Integer getLoyaltyPointsEarned() {
        return loyaltyPointsEarned;
    }

    public Integer getLoyaltyPointsRedeemed() {
        return loyaltyPointsRedeemed;
    }

    public Staff getStaff() {
        return staff;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getEligibilityAt() {
        return eligibilityAt;
    }

    /** "Edit Order": the order is judged as of now, as when it was first placed. */
    public void refreshEligibility(Instant at) {
        requireStatus(OrderStatus.OPEN);
        this.eligibilityAt = at;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public Customer getCustomer() {
        return customer;
    }

    public String getVoidReason() {
        return voidReason;
    }

    public Staff getVoidedBy() {
        return voidedBy;
    }

    public Instant getVoidedAt() {
        return voidedAt;
    }

    public boolean isRefunded() {
        return refundedAt != null;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public Staff getRefundedBy() {
        return refundedBy;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public List<SaleOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public List<Payment> getPayments() {
        return Collections.unmodifiableList(payments);
    }
}
