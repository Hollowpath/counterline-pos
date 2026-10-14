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

    /** Pays a placed order in full. Retrying with the same request id returns the paid order. */
    public OrderResponse pay(Long orderId, PayOrderRequest request, Terminal terminal) {
        Long staffId = terminal.staffId();
        SaleOrder order = findOrder(orderId, terminal.outletId());
        if (request.clientRequestId().equals(order.getPaymentRequestId())) {
            return OrderResponse.from(order);
        }
        if (order.getStatus() != OrderStatus.OPEN) {
            throw new BusinessRuleException("Order " + order.getDisplayNumber() + " is not awaiting payment");
        }
        registerGate.requireOpenForPayment(terminal.outletId());
        Staff staff = activeStaff(staffId);
        Instant now = clock.instant();
        // Placing an order checks nothing, so paying it checks everything (the member's points, locked,
        // as they stand now). A comp a manager hasn't approved is paid through Edit Order, which asks.
        if (order.getDiscounts().stream().anyMatch(discount -> discount.getDiscount().isRequiresApproval()
                && discount.getApprovedBy() == null)) {
            throw new BusinessRuleException("A manager has to approve this order's discount: pay it with Edit Order");
        }
        int pointsBalance = order.getCustomer() == null ? 0 : loyaltyService.lock(order.getCustomer().getId(), now);
        promotionRules.requireSatisfied(order, pointsBalance, now, order.getId());
        paymentRecorder.addPayments(order, request.payments(), staff, now);
        order.closeWithPaymentRequest(request.clientRequestId(), now);
        orderRepository.saveAndFlush(order);
        orderLoyalty.settle(order, now);
        orderRepository.flush();
        log.info("Order {} paid: RM{} by staff {}", order.getDisplayNumber(), order.getTotal(), staffId);
        return OrderResponse.from(order);
    }

    public OrderResponse voidOrder(Long orderId, String reason, Terminal manager) {
        Long managerId = manager.staffId();
        SaleOrder order = findOrder(orderId, manager.outletId());
        Instant now = clock.instant();
        order.voidOrder(reason.strip(), activeStaff(managerId), now);
        orderRepository.saveAndFlush(order);
        voucherService.releaseFor(order.getId());
        log.info("Order {} voided by staff {}", order.getDisplayNumber(), managerId);
        return OrderResponse.from(order);
    }

    public OrderResponse refund(Long orderId, String reason, Terminal manager) {
        Long managerId = manager.staffId();
        SaleOrder order = findOrder(orderId, manager.outletId());
        Instant now = clock.instant();
        order.refund(reason.strip(), activeStaff(managerId), now);
        orderLoyalty.reverse(order, now);
        orderRepository.saveAndFlush(order);
        // The whole sale is undone, so the customer gets their voucher back too.
        voucherService.releaseFor(order.getId());
        log.info("Order {} refunded RM{} by staff {}", order.getDisplayNumber(), order.getAmountPaid(), managerId);
        return OrderResponse.from(order);
    }

    /** Only the outlet's own orders: another outlet's are not found. */
    private SaleOrder findOrder(Long orderId, Long outletId) {
        return orderRepository.findByIdAndOutletId(orderId, outletId)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    }

    private Staff activeStaff(Long staffId) {
        return staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new BadCredentialsException("Staff account is no longer active"));
    }
}
