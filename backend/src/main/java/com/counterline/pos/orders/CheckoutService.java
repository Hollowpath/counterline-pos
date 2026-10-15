package com.counterline.pos.orders;

import com.counterline.pos.auth.ManagerApproval;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.catalog.CatalogDtos.PromotionSummary;
import com.counterline.pos.catalog.CatalogService;
import com.counterline.pos.catalog.Item;
import com.counterline.pos.catalog.ModifierGroup;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.discounts.Discount;
import com.counterline.pos.discounts.DiscountService;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.orders.OrderDtos.CheckoutMode;
import com.counterline.pos.orders.OrderDtos.CheckoutRequest;
import com.counterline.pos.orders.OrderDtos.EditableDiscount;
import com.counterline.pos.orders.OrderDtos.EditableLine;
import com.counterline.pos.orders.OrderDtos.EditableModifier;
import com.counterline.pos.orders.OrderDtos.EditableOrder;
import com.counterline.pos.orders.OrderDtos.LineRequest;
import com.counterline.pos.orders.OrderDtos.LoyaltyPreview;
import com.counterline.pos.orders.OrderDtos.ModifierPick;
import com.counterline.pos.orders.OrderDtos.OrderCustomer;
import com.counterline.pos.orders.OrderDtos.OrderResponse;
import com.counterline.pos.orders.OrderDtos.QuoteResponse;
import com.counterline.pos.register.RegisterGate;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.vouchers.Voucher;
import com.counterline.pos.vouchers.VoucherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns a cart into an order, atomically: either paid in full (CLOSED) or placed unpaid (OPEN).
 *
 * <p>Idempotent per {@code clientRequestId}: a retried or double-submitted checkout returns the
 * order created the first time instead of recording the sale twice. The transaction is managed
 * explicitly because a duplicate detected by the unique constraint aborts that transaction, and
 * the existing order then has to be read in a fresh one.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

    private final TransactionTemplate transaction;
    private final SaleOrderRepository orderRepository;
    private final StaffRepository staffRepository;
    private final CatalogService catalogService;
    private final CustomerService customerService;
    private final DiscountService discountService;
    private final PaymentRecorder paymentRecorder;
    private final OrderQueryService orderQueries;
    private final ReceiptCodeGenerator receiptCodes;
    private final RegisterGate registerGate;
    private final PromotionRules promotionRules;
    private final LoyaltyService loyaltyService;
    private final OrderLoyalty orderLoyalty;
    private final ManagerApproval managerApproval;
    private final VoucherService voucherService;
    private final TransactionTemplate readOnlyTransaction;
    private final Clock clock;

    CheckoutService(PlatformTransactionManager transactionManager,
                    SaleOrderRepository orderRepository,
                    StaffRepository staffRepository,
                    CatalogService catalogService,
                    CustomerService customerService,
                    DiscountService discountService,
                    PaymentRecorder paymentRecorder,
                    OrderQueryService orderQueries,
                    ReceiptCodeGenerator receiptCodes,
                    RegisterGate registerGate,
                    PromotionRules promotionRules,
                    LoyaltyService loyaltyService,
                    OrderLoyalty orderLoyalty,
                    ManagerApproval managerApproval,
                    VoucherService voucherService,
                    Clock clock) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
        this.orderRepository = orderRepository;
        this.staffRepository = staffRepository;
        this.catalogService = catalogService;
        this.customerService = customerService;
        this.discountService = discountService;
        this.paymentRecorder = paymentRecorder;
        this.orderQueries = orderQueries;
        this.receiptCodes = receiptCodes;
        this.registerGate = registerGate;
        this.promotionRules = promotionRules;
        this.loyaltyService = loyaltyService;
        this.orderLoyalty = orderLoyalty;
        this.managerApproval = managerApproval;
        this.voucherService = voucherService;
        this.clock = clock;
    }

    public record CheckoutResult(OrderResponse order, boolean created) {
    }

    /** Records the sale at the terminal's outlet, by the staff member logged in there. */
    public CheckoutResult checkout(CheckoutRequest request, Terminal terminal) {
        Optional<OrderResponse> replay = orderQueries.findByClientRequestId(request.clientRequestId(), terminal.outletId());
        if (replay.isPresent()) {
            return new CheckoutResult(replay.get(), false);
        }
        try {
            OrderResponse order = transaction.execute(status -> createOrder(request, terminal));
            log.info("Order {} {} at outlet {}: total RM{} by staff {}", order.orderNumber(),
                    order.status() == OrderStatus.OPEN ? "placed" : "closed", terminal.outletId(), order.total(),
                    terminal.staffId());
            return new CheckoutResult(order, true);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent request carrying the same clientRequestId.
            return orderQueries.findByClientRequestId(request.clientRequestId(), terminal.outletId())
                    .map(existing -> new CheckoutResult(existing, false))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Prices the cart as checkout would and lists why it could not be paid yet (promotion rules),
     * with the member's points preview. Nothing is saved. {@code editedOrderId}: the placed order the
     * cart is editing, if any (its own vouchers and once-only rewards are still its own).
     */
    public QuoteResponse quote(CheckoutRequest request, Terminal terminal, Long editedOrderId) {
        return readOnlyTransaction.execute(status -> {
            Instant now = clock.instant();
            List<String> problems = new ArrayList<>();
            Discount voucherDiscount = null;
            if (request.hasVoucher()) {
                VoucherService.QuoteCheck voucher = voucherService.forQuote(request.voucherCode(),
                        editedOrderId == null ? 0L : editedOrderId);
                voucherDiscount = voucher.discount();
                if (voucher.problem() != null) {
                    problems.add(voucher.problem());
                }
            }
            SaleOrder order = buildOrder(request, activeStaff(terminal.staffId()), terminal.outletId(), now, 0, "",
                    voucherDiscount);
            Customer member = order.getCustomer();
            int balance = member == null ? 0 : loyaltyService.balance(member.getId());
            LoyaltyPreview loyalty = member == null ? null
                    : LoyaltyPreview.of(loyaltyService.preview(member.getId(), order.getTotal(), order.getPointsToRedeem()));
            problems.addAll(promotionRules.problems(order, balance, now, editedOrderId == null ? 0L : editedOrderId));
            return new QuoteResponse(order.getSubtotal(), order.getDiscountTotal(), order.getTotal(), problems, loyalty);
        });
    }

    private OrderResponse createOrder(CheckoutRequest request, Terminal terminal) {
        Staff staff = activeStaff(terminal.staffId());
        Instant now = clock.instant();
        // Locked until this transaction ends, so another till can't use the same voucher meanwhile.
        Voucher voucher = request.hasVoucher() ? voucherService.forSale(request.voucherCode(), true) : null;
        SaleOrder order = buildOrder(request, staff, terminal.outletId(), now, orderRepository.nextOrderNumber(),
                receiptCodes.next(), voucher == null ? null : voucherService.discountOf(voucher));
        requireReadyToPay(order, request, staff, now, 0L);
        complete(order, request, voucher, staff, terminal, now, null);
        return OrderResponse.from(order);
    }

    /**
     * Before paying: a manager's approval for a department comp, and the promotion rules. Placing an
     * order is never refused for these: it is saved as it is, and they apply when it is paid (Edit
     * Order → Pay).
     */
    private void requireReadyToPay(SaleOrder order, CheckoutRequest request, Staff staff, Instant now,
                                   long editedOrderId) {
        if (request.modeOrDefault() == CheckoutMode.PLACE_ORDER) {
            return;
        }
        if (order.needsDiscountApproval()) {
            order.approveDiscounts(managerApproval.requireManager(staff, request.approvalToken()));
        }
        // The member's account stays locked until this transaction ends, so the checks below
        // (points, once-only rewards) cannot race another terminal's sale for the same member.
        int pointsBalance = order.getCustomer() == null ? 0 : loyaltyService.lock(order.getCustomer().getId(), now);
        promotionRules.requireSatisfied(order, pointsBalance, now, editedOrderId);
    }

    /**
     * Places the order (OPEN, unpaid) or pays and closes it. {@code paymentRequestId}: for an edited
     * order paid now, so a retry finds it paid.
     */
    private void complete(SaleOrder order, CheckoutRequest request, Voucher voucher, Staff staff, Terminal terminal,
                          Instant now, UUID paymentRequestId) {
        // Placing an order, like paying, needs the outlet's register open.
        registerGate.requireOpenForPayment(terminal.outletId());
        if (request.modeOrDefault() == CheckoutMode.PLACE_ORDER) {
            paymentRecorder.addPayments(order, request.payments(), staff, now);
            order.place();
            orderRepository.saveAndFlush(order);
            useVoucher(voucher, order);
        } else {
            paymentRecorder.addPayments(order, request.payments(), staff, now);
            if (paymentRequestId == null) {
                order.close(now);
            } else {
                order.closeWithPaymentRequest(paymentRequestId, now);
            }
            // Flush inside the transaction so constraint violations surface here, not at commit.
            orderRepository.saveAndFlush(order);
            useVoucher(voucher, order);
            orderLoyalty.settle(order, now);
            orderRepository.flush();
        }
    }

    /**
     * "Edit Order": a placed (unpaid) order as a cart, at today's prices, for the Point of Sale.
     *
     * @throws BusinessRuleException if the order has been paid or voided
     */
    public EditableOrder editable(Long orderId, Long outletId) {
        return readOnlyTransaction.execute(status -> {
            SaleOrder order = orderRepository.findByIdAndOutletId(orderId, outletId)
                    .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
            if (order.getStatus() != OrderStatus.OPEN) {
                throw new BusinessRuleException("Order " + order.getDisplayNumber() + " is not awaiting payment");
            }
            String voucherCode = voucherService.codeUsedBy(order.getId()).orElse(null);
            List<EditableLine> lines = order.getLines().stream().map(line -> editableLine(order, line)).toList();
            List<EditableDiscount> orderDiscounts = order.getDiscounts().stream()
                    .filter(discount -> discount.getLine() == null)
                    .map(discount -> EditableDiscount.of(discount.getDiscount(),
                            discount.getDiscount().getScope() == Discount.Scope.VOUCHER ? voucherCode : null))
                    .toList();
            return new EditableOrder(order.getId(), order.getDisplayNumber(), order.getNote(),
                    OrderCustomer.from(order.getCustomer()), lines, orderDiscounts);
        });
    }

    private static EditableLine editableLine(SaleOrder order, SaleOrderLine line) {
        List<EditableDiscount> discounts = order.getDiscounts().stream()
                .filter(discount -> discount.belongsTo(line))
                .map(discount -> EditableDiscount.of(discount.getDiscount(), null))
                .toList();
        List<EditableModifier> modifiers = line.getModifiers().stream()
                .map(modifier -> new EditableModifier(modifier.getOption().getId(), modifier.getOptionName(),
                        modifier.getQuantity(), modifier.getOption().getPrice()))
                .toList();
        Item item = line.getItem();
        if (item == null) {
            return new EditableLine(null, line.getItemName(), line.getUnitPrice(), line.getQuantity(), line.getNote(),
                    modifiers, discounts, null, false);
        }
        return new EditableLine(item.getId(), item.getName(), item.getPrice(), line.getQuantity(), line.getNote(),
                modifiers, discounts, PromotionSummary.of(item.getPromotion()), item.getCategory().isCountsForPromotions());
    }

    /**
     * "Edit Order": replaces a placed order's items, customer, discounts and note with the cart's,
     * then places it again or pays it ({@link CheckoutMode}). It keeps its order number and receipt
     * code. The order is locked meanwhile, so another till can't edit or pay it at the same time.
     * Paying is idempotent per {@code clientRequestId}: a retry returns the paid order.
     */
    public OrderResponse edit(Long orderId, CheckoutRequest request, Terminal terminal) {
        OrderResponse edited = transaction.execute(status -> {
            SaleOrder order = orderRepository.lockByIdAndOutletId(orderId, terminal.outletId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
            if (request.clientRequestId().equals(order.getPaymentRequestId())) {
                return OrderResponse.from(order);
            }
            if (order.getStatus() != OrderStatus.OPEN) {
                throw new BusinessRuleException("Order " + order.getDisplayNumber() + " is not awaiting payment");
            }
            Staff staff = activeStaff(terminal.staffId());
            Instant now = clock.instant();

            // Empty it (the discounts first: they refer to the lines), then fill it from the cart.
            voucherService.releaseFor(order.getId());
            order.removeDeductions();
            orderRepository.saveAndFlush(order);
            order.clearForEdit(blankToNull(request.note()));
            orderRepository.saveAndFlush(order);

            Voucher voucher = request.hasVoucher() ? voucherService.forSale(request.voucherCode(), true) : null;
            fill(order, request, voucher == null ? null : voucherService.discountOf(voucher));
            requireReadyToPay(order, request, staff, now, order.getId());
            complete(order, request, voucher, staff, terminal, now, request.clientRequestId());
            return OrderResponse.from(order);
        });
        log.info("Order {} edited and {} at outlet {} by staff {}", edited.orderNumber(),
                edited.status() == OrderStatus.OPEN ? "placed" : "paid", terminal.outletId(), terminal.staffId());
        return edited;
    }

    private void useVoucher(Voucher voucher, SaleOrder order) {
        if (voucher != null) {
            voucherService.markUsed(voucher, order.getId());
        }
    }

    /**
     * The order as the cart describes it, priced from the catalogue; not saved ({@code outletId} null
     * for a quote). {@code voucherDiscount}: a voucher's, applied as the order's discount.
     */
    private SaleOrder buildOrder(CheckoutRequest request, Staff staff, Long outletId, Instant now, long orderNumber,
                                 String receiptCode, Discount voucherDiscount) {
        SaleOrder order = new SaleOrder(outletId, orderNumber, receiptCode, request.clientRequestId(),
                blankToNull(request.note()), staff, now);
        fill(order, request, voucherDiscount);
        return order;
    }

    /** Fills an empty order from the cart: its customer, lines (priced from the catalogue) and discounts. */
    private void fill(SaleOrder order, CheckoutRequest request, Discount voucherDiscount) {
        if (request.customerId() != null) {
            order.assignCustomer(customerService.getForSale(request.customerId()));
        }

        Set<Long> itemIds = request.lines().stream()
                .map(LineRequest::itemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, Item> items = catalogService.getActiveItems(itemIds);
        Map<Long, List<ModifierGroup>> groups = catalogService.getModifierGroups(itemIds);

        for (LineRequest line : request.lines()) {
            List<Discount> lineDiscounts = discountService.resolve(line.discountIdsOrEmpty(), Discount.Scope.ITEM);
            String note = blankToNull(line.note());
            if (line.customAmount() != null) {
                order.addCustomAmountLine(line.customAmount(), line.quantity(), note, lineDiscounts);
                continue;
            }
            Item item = items.get(line.itemId());
            if (item == null) {
                throw new BusinessRuleException("Item " + line.itemId() + " is no longer available");
            }
            order.addItemLine(item, groups.getOrDefault(item.getId(), List.of()), picks(line),
                    line.quantity(), note, lineDiscounts);
        }
        List<Discount> orderDiscounts = new ArrayList<>(discountService.resolve(request.discountIdsOrEmpty(), Discount.Scope.ORDER));
        if (voucherDiscount != null) {
            if (!orderDiscounts.isEmpty() || !order.getDiscounts().isEmpty()) {
                throw new BusinessRuleException("1 discount per sale");
            }
            orderDiscounts.add(voucherDiscount);
        }
        order.applyOrderDiscounts(orderDiscounts);
    }

    private Staff activeStaff(Long staffId) {
        return staffRepository.findByIdAndActiveTrue(staffId)
                .orElseThrow(() -> new BadCredentialsException("Staff account is no longer active"));
    }

    /** option id → times picked per unit; listing an option twice is a client error, not a merge. */
    private static Map<Long, Integer> picks(LineRequest line) {
        Map<Long, Integer> picks = new HashMap<>();
        for (ModifierPick pick : line.modifiersOrEmpty()) {
            if (picks.put(pick.optionId(), pick.quantity()) != null) {
                throw new BusinessRuleException("Option " + pick.optionId() + " is listed twice");
            }
        }
        return picks;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
