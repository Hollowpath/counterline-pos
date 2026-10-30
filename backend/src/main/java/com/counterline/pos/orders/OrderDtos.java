package com.counterline.pos.orders;

import com.counterline.pos.catalog.CatalogDtos.PromotionSummary;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.discounts.Discount;
import com.counterline.pos.loyalty.LoyaltyService.Settlement;
import com.counterline.pos.staff.Staff;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class OrderDtos {

    private OrderDtos() {
    }

    /** The two cart buttons: Pay now, or Place Order (save unpaid, pay later). */
    public enum CheckoutMode {
        PAY,
        PLACE_ORDER
    }

    /**
     * A sale from the cart: what was sold and, for {@link CheckoutMode#PAY}, how it was paid.
     * Prices are deliberately absent; the server prices every line from the catalogue.
     */
    public record CheckoutRequest(
            @NotNull UUID clientRequestId,
            CheckoutMode mode,
            /* Optional: the customer the sale is made to. */
            Long customerId,
            @Size(max = 500) String note,
            @NotEmpty @Size(max = 100) List<@Valid @NotNull LineRequest> lines,
            @NotNull @Size(max = 10) List<@Valid @NotNull PaymentRequest> payments,
            /* Order-level discounts (the % tab); optional. */
            @Size(max = 20) List<@NotNull Long> discountIds,
            /* A manager's special-access token, when a discount needs approval (department comps). */
            @Size(max = 2000) String approvalToken,
            /* A physical voucher's code: its value comes off the order, as the sale's one discount. */
            @Size(max = 20) String voucherCode) {

        /** Without order-level discounts. */
        public CheckoutRequest(UUID clientRequestId, CheckoutMode mode, Long customerId, String note,
                               List<LineRequest> lines, List<PaymentRequest> payments) {
            this(clientRequestId, mode, customerId, note, lines, payments, null, null, null);
        }

        /** Without a manager's approval or a voucher. */
        public CheckoutRequest(UUID clientRequestId, CheckoutMode mode, Long customerId, String note,
                               List<LineRequest> lines, List<PaymentRequest> payments, List<Long> discountIds) {
            this(clientRequestId, mode, customerId, note, lines, payments, discountIds, null, null);
        }

        public boolean hasVoucher() {
            return voucherCode != null && !voucherCode.isBlank();
        }

        public CheckoutMode modeOrDefault() {
            return mode == null ? CheckoutMode.PAY : mode;
        }

        public List<Long> discountIdsOrEmpty() {
            return discountIds == null ? List.of() : discountIds;
        }
    }

    /**
     * A catalogue item ({@code itemId}, with its options per unit) or a custom amount from the
     * keypad ({@code customAmount}) — exactly one of the two.
     */
    public record LineRequest(
            Long itemId,
            @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal customAmount,
            @NotNull @Min(1) @Max(999) Integer quantity,
            @Size(max = 200) String note,
            @Size(max = 50) List<@Valid @NotNull ModifierPick> modifiers,
            @Size(max = 20) List<@NotNull Long> discountIds) {

        /** A simple item: no options, note or discounts. */
        public LineRequest(Long itemId, Integer quantity) {
            this(itemId, null, quantity, null, null, null);
        }

        @AssertTrue(message = "a line needs either itemId or customAmount, not both")
        public boolean isItemOrCustomAmount() {
            return (itemId == null) != (customAmount == null);
        }

        public List<ModifierPick> modifiersOrEmpty() {
            return modifiers == null ? List.of() : modifiers;
        }

        public List<Long> discountIdsOrEmpty() {
            return discountIds == null ? List.of() : discountIds;
        }
    }

    /** An option picked {@code quantity} times for each unit of the line. */
    public record ModifierPick(@NotNull Long optionId, @NotNull @Min(1) @Max(99) Integer quantity) {
    }

    /**
     * {@code reference} is required for cashless methods (from the card or QR terminal).
     * {@code cashTendered} is optional and only for cash: what the customer handed over.
     */
    public record PaymentRequest(
            @NotNull Long paymentMethodId,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 60) String reference,
            @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal cashTendered) {

        public PaymentRequest(Long paymentMethodId, BigDecimal amount, String reference) {
            this(paymentMethodId, amount, reference, null);
        }
    }

    /** Pays a placed (OPEN) order in full. */
    public record PayOrderRequest(
            @NotNull UUID clientRequestId,
            @NotNull @Size(max = 10) List<@Valid @NotNull PaymentRequest> payments) {
    }

    /** Void and refund both require a reason. */
    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {
    }

    public record OrderResponse(
            Long id,
            String orderNumber,
            String receiptCode,
            OrderStatus status,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal total,
            BigDecimal amountPaid,
            String note,
            String staffName,
            OrderCustomer customer,
            Instant createdAt,
            /* When it was paid; null while unpaid. The day it is reported on. */
            Instant closedAt,
            List<LineResponse> lines,
            List<AppliedDiscount> orderDiscounts,
            List<PaymentResponse> payments,
            VoidResponse voided,
            RefundResponse refund,
            /* The member's points around this sale; null until a member's sale is paid. */
            LoyaltyPreview loyalty) {

        static OrderResponse from(SaleOrder order) {
            return new OrderResponse(
                    order.getId(),
                    order.getDisplayNumber(),
                    order.getReceiptCode(),
                    order.getStatus(),
                    order.getSubtotal(),
                    order.getDiscountTotal(),
                    order.getTotal(),
                    order.getAmountPaid(),
                    order.getNote(),
                    order.getStaff().getFullName(),
                    OrderCustomer.from(order.getCustomer()),
                    order.getCreatedAt(),
                    order.getClosedAt(),
                    order.getLines().stream().map(line -> LineResponse.from(line, order)).toList(),
                    order.getDiscounts().stream()
                            .filter(discount -> discount.getLine() == null)
                            .map(AppliedDiscount::from)
                            .toList(),
                    order.getPayments().stream().map(PaymentResponse::from).toList(),
                    order.getVoidedAt() == null ? null
                            : new VoidResponse(order.getVoidReason(), name(order.getVoidedBy()), order.getVoidedAt()),
                    order.isRefunded()
                            ? new RefundResponse(order.getAmountPaid(), order.getRefundReason(),
                                    name(order.getRefundedBy()), order.getRefundedAt())
                            : null,
                    order.getLoyaltyPointsBefore() == null ? null
                            : new LoyaltyPreview(order.getLoyaltyPointsBefore(), order.getLoyaltyPointsEarned(),
                                    order.getLoyaltyPointsRedeemed(),
                                    order.getLoyaltyPointsBefore() + order.getLoyaltyPointsEarned()
                                            - order.getLoyaltyPointsRedeemed(), null));
        }

        private static String name(Staff staff) {
            return staff.getFullName();
        }
    }

    /** {@code itemId} is null for a custom amount; amounts follow SaleOrderLine's formulas. */
    public record LineResponse(int lineNumber, Long itemId, String itemName, String note, BigDecimal unitPrice,
                               BigDecimal modifierUnitPrice, int quantity, List<LineModifier> modifiers,
                               BigDecimal lineSubtotal, List<AppliedDiscount> discounts, BigDecimal discountTotal,
                               BigDecimal lineTotal) {

        /** {@code discounts}: the line discount, or the promotion that made a promotional line free. */
        static LineResponse from(SaleOrderLine line, SaleOrder order) {
            List<AppliedDiscount> deductions = Stream.concat(
                            order.getDiscounts().stream().filter(discount -> discount.belongsTo(line)).map(AppliedDiscount::from),
                            order.getPromotions().stream().filter(promotion -> promotion.belongsTo(line)).map(AppliedDiscount::from))
                    .toList();
            return new LineResponse(line.getLineNumber(), line.getItemId(), line.getItemName(), line.getNote(),
                    line.getUnitPrice(), line.getModifierUnitPrice(), line.getQuantity(),
                    line.getModifiers().stream()
                            .map(m -> new LineModifier(m.getGroupName(), m.getOptionName(), m.getQuantity(), m.getUnitPrice()))
                            .toList(),
                    line.getLineSubtotal(), deductions, line.getDiscountTotal(), line.getLineTotal());
        }
    }

    public record LineModifier(String groupName, String optionName, int quantity, BigDecimal unitPrice) {
    }

    /** A discount or promotion deduction; {@code promotion} is true for the latter. */
    /** A discount, or the promotion that made a promotional line free. {@code detail}: who approved a department comp. */
    public record AppliedDiscount(String name, BigDecimal amount, boolean promotion, String detail) {

        static AppliedDiscount from(SaleOrderDiscount discount) {
            String approvedBy = discount.getApprovedBy() == null ? null
                    : "Approved by " + discount.getApprovedBy().getFullName();
            return new AppliedDiscount(discount.getName(), discount.getAmount(), false, approvedBy);
        }

        static AppliedDiscount from(SaleOrderPromotion promotion) {
            return new AppliedDiscount(promotion.getName(), promotion.getAmount(), true, null);
        }
    }

    /**
     * The receipt's LOYALTY PROGRAM block: points before, earned, redeemed, and after the sale. {@code expiresAt}: when
     * the points left after the sale expire (365 days after this purchase, the member's latest); only in a cart's
     * preview, null on a paid order and when there are no points to expire.
     */
    public record LoyaltyPreview(int before, int earned, int redeemed, int after, Instant expiresAt) {

        static LoyaltyPreview of(Settlement settlement, Instant expiresAt) {
            return new LoyaltyPreview(settlement.before(), settlement.earned(), settlement.redeemed(), settlement.after(), expiresAt);
        }
    }

    /**
     * The cart priced by the server. {@code problems} say why it cannot be paid yet (promotion
     * rules); {@code loyalty} previews the member's points.
     */
    public record QuoteResponse(BigDecimal subtotal, BigDecimal discountTotal, BigDecimal total,
                                List<String> problems, LoyaltyPreview loyalty) {
    }

    public record PaymentResponse(Long id, String method, PaymentMethod.Kind kind, BigDecimal amount,
                                  String reference, BigDecimal cashTendered, BigDecimal change, Instant createdAt) {

        static PaymentResponse from(Payment payment) {
            return new PaymentResponse(payment.getId(), payment.getPaymentMethod().getName(),
                    payment.getPaymentMethod().getKind(), payment.getAmount(), payment.getReference(),
                    payment.getCashTendered(), payment.getChange(), payment.getCreatedAt());
        }
    }

    public record VoidResponse(String reason, String voidedBy, Instant voidedAt) {
    }

    public record RefundResponse(BigDecimal amount, String reason, String refundedBy, Instant refundedAt) {
    }

    /** The customer a sale was made to, as shown on the order. */
    public record OrderCustomer(Long id, String name, String phone, String email) {

        static OrderCustomer from(Customer customer) {
            return customer == null ? null
                    : new OrderCustomer(customer.getId(), customer.getFullName(), customer.getPhone(), customer.getEmail());
        }
    }

    /**
     * A placed (unpaid) order as the till's cart: "Edit Order" loads it back into the Point of Sale,
     * at today's prices. Discounts carry their terms; a voucher's carries its code.
     */
    public record EditableOrder(Long id, String orderNumber, String note, OrderCustomer customer,
                                List<EditableLine> lines, List<EditableDiscount> orderDiscounts) {
    }

    /** {@code itemId} null for a custom amount (then {@code unitPrice} is the amount keyed in). */
    public record EditableLine(Long itemId, String name, BigDecimal unitPrice, int quantity, String note,
                               List<EditableModifier> modifiers, List<EditableDiscount> discounts,
                               PromotionSummary promotion, boolean countsForPromotions) {
    }

    public record EditableModifier(Long optionId, String name, int quantity, BigDecimal price) {
    }

    public record EditableDiscount(Long id, String name, Discount.Kind kind, BigDecimal value, boolean requiresApproval,
                                   boolean notForMembers, boolean countsAsVoucher, String voucherCode) {

        static EditableDiscount of(Discount discount, String voucherCode) {
            return new EditableDiscount(discount.getId(), discount.getName(), discount.getKind(), discount.getValue(),
                    discount.isRequiresApproval(), discount.isNotForMembers(), discount.isVoucher(), voucherCode);
        }
    }

    /** A row in the Past Orders list; customerName is the tag shown on the row. */
    public record OrderSummary(Long id, String orderNumber, String receiptCode, OrderStatus status,
                               boolean refunded, BigDecimal total, String itemSummary, String customerName,
                               Instant createdAt) {
    }

    /** Customer Analytics: from the customer's paid, non-refunded orders. */
    public record CustomerAnalytics(long orderCount, BigDecimal orderTotal, BigDecimal orderAverage,
                                    Instant lastOrderAt, List<TopItem> mostOrderedItems) {
    }

    public record TopItem(String itemName, long quantity) {
    }

    public record PaymentMethodResponse(Long id, String name, PaymentMethod.Kind kind) {
    }
}
