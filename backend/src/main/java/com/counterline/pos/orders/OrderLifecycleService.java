package com.counterline.pos.orders;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.orders.OrderDtos.OrderResponse;
import com.counterline.pos.orders.OrderDtos.PayOrderRequest;
import com.counterline.pos.register.RegisterGate;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.vouchers.VoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * State changes on existing orders. Concurrent changes to the same order are caught by the
 * order's version column (optimistic locking) and reported as 409 Conflict.
 * Who may void or refund is enforced at the controller (manager role).
 */
@Service
@Transactional
public class OrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(OrderLifecycleService.class);

    private final SaleOrderRepository orderRepository;
    private final StaffRepository staffRepository;
    private final PaymentRecorder paymentRecorder;
    private final RegisterGate registerGate;
    private final OrderLoyalty orderLoyalty;
    private final LoyaltyService loyaltyService;
    private final PromotionRules promotionRules;
    private final VoucherService voucherService;
    private final Clock clock;

    OrderLifecycleService(SaleOrderRepository orderRepository, StaffRepository staffRepository,
                          PaymentRecorder paymentRecorder, RegisterGate registerGate, OrderLoyalty orderLoyalty,
                          LoyaltyService loyaltyService, PromotionRules promotionRules, VoucherService voucherService,
                          Clock clock) {
        this.orderRepository = orderRepository;
        this.staffRepository = staffRepository;
        this.paymentRecorder = paymentRecorder;
        this.registerGate = registerGate;
        this.orderLoyalty = orderLoyalty;
        this.loyaltyService = loyaltyService;
        this.promotionRules = promotionRules;
        this.voucherService = voucherService;
        this.clock = clock;
    }

    /**
     * Pays a placed order in full. Retrying with the same request id returns the paid order, also when the
     * retry is a double-tap that arrives while the first is still being processed: the order is locked, so
     * the second request waits and then finds it paid. Locks are always taken in the order: the sale, then the
     * member's points, then the register.
     */
    public OrderResponse pay(Long orderId, PayOrderRequest request, Terminal terminal) {
        Long staffId = terminal.staffId();
        SaleOrder order = lockOrder(orderId, terminal.outletId());
        if (request.clientRequestId().equals(order.getPaymentRequestId())) {
            return OrderResponse.from(order);
        }
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new BusinessRuleException("Order " + order.getDisplayNumber() + " is not awaiting payment");
        }
        Staff staff = activeStaff(staffId);
        Instant now = clock.instant();
        // Placing an order checks nothing, so paying it checks everything (the member's points, locked,
        // as they stand now). A comp a manager hasn't approved is paid through Edit Order, which asks.
        if (order.getDiscounts().stream().anyMatch(discount -> discount.getDiscount().isRequiresApproval()
                && discount.getApprovedBy() == null)) {
            throw new BusinessRuleException("Manager approval needed: use Edit Order");
        }
        voucherService.requireStillValidFor(order.getId());
        // The member's points account before the register, as at checkout: the same lock order
        // everywhere, so a payment and a sale for one member can't deadlock with a register close.
        int pointsBalance = order.getCustomer() == null ? 0 : loyaltyService.lock(order.getCustomer().getId(), now);
        // The payment is dated once the register is locked, so it can't fall in a session that closed meanwhile.
        Instant paidAt = registerGate.lockForDrawerEvent(terminal.outletId());
        // Judged as of when it was placed or last edited; the points and once-only rewards, as they are now.
        promotionRules.requireSatisfied(order, pointsBalance, order.getEligibilityAt(), order.getId());
        paymentRecorder.addPayments(order, request.payments(), staff, paidAt);
        order.closeWithPaymentRequest(request.clientRequestId(), paidAt);
        orderRepository.saveAndFlush(order);
        orderLoyalty.settle(order, paidAt);
        orderRepository.flush();
        log.info("Order {} paid: RM{} by staff {}", order.getDisplayNumber(), order.getTotal(), staffId);
        return OrderResponse.from(order);
    }

    /** An unpaid order can be voided with the register closed: no money moves. */
    public OrderResponse voidOrder(Long orderId, String reason, Terminal manager) {
        Long managerId = manager.staffId();
        SaleOrder order = lockOrder(orderId, manager.outletId());
        Instant now = clock.instant();
        order.voidOrder(reason.strip(), activeStaff(managerId), now);
        orderRepository.saveAndFlush(order);
        voucherService.releaseFor(order.getId());
        log.info("Order {} voided by staff {}", order.getDisplayNumber(), managerId);
        return OrderResponse.from(order);
    }

    /**
     * Refunds a paid order in full. The register must be open: the cash goes out of a drawer, so every refund
     * belongs to a session. (Locks: the sale, the member's points, then the register, as when paying.)
     */
    public OrderResponse refund(Long orderId, String reason, Terminal manager) {
        Long managerId = manager.staffId();
        SaleOrder order = lockOrder(orderId, manager.outletId());
        Staff staff = activeStaff(managerId);
        order.requireRefundable();
        // The points are taken back (locking the member's account) before the register is locked, and the
        // refund is dated only after that lock, so it belongs to the session that is open when it is made.
        orderLoyalty.reverse(order, clock.instant());
        Instant refundedAt = registerGate.lockForDrawerEvent(manager.outletId());
        order.refund(reason.strip(), staff, refundedAt);
        orderRepository.saveAndFlush(order);
        // The whole sale is undone, so the customer gets their voucher back too.
        voucherService.releaseFor(order.getId());
        log.info("Order {} refunded RM{} by staff {}", order.getDisplayNumber(), order.getAmountPaid(), managerId);
        return OrderResponse.from(order);
    }

    /**
     * Only the outlet's own orders: another outlet's are not found. Locked until the transaction ends, so
     * paying, voiding, refunding and editing one order happen one at a time and the later ones see the
     * earlier one's result (and refuse with the usual "not awaiting payment" or "already refunded").
     */
    private SaleOrder lockOrder(Long orderId, Long outletId) {
        return orderRepository.lockByIdAndOutletId(orderId, outletId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    }

    private Staff activeStaff(Long staffId) {
        return staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new BadCredentialsException("Staff account is no longer active"));
    }
}
