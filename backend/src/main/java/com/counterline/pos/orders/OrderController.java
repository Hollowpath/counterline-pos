package com.counterline.pos.orders;

import com.counterline.pos.auth.ApprovalOperation;
import com.counterline.pos.auth.ApprovalTokens;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.orders.CheckoutService.CheckoutResult;
import com.counterline.pos.orders.OrderDtos.CheckoutRequest;
import com.counterline.pos.orders.OrderDtos.EditableOrder;
import com.counterline.pos.orders.OrderDtos.OrderResponse;
import com.counterline.pos.orders.OrderDtos.QuoteResponse;
import com.counterline.pos.orders.OrderDtos.OrderSummary;
import com.counterline.pos.common.PageResponse;
import com.counterline.pos.orders.OrderDtos.PayOrderRequest;
import com.counterline.pos.orders.OrderDtos.PaymentMethodResponse;
import com.counterline.pos.orders.OrderDtos.ReasonRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearer")
class OrderController {

    private final CheckoutService checkoutService;
    private final OrderLifecycleService lifecycle;
    private final OrderQueryService orderQueries;
    private final ApprovalTokens approvalTokens;

    OrderController(CheckoutService checkoutService, OrderLifecycleService lifecycle, OrderQueryService orderQueries,
                    ApprovalTokens approvalTokens) {
        this.checkoutService = checkoutService;
        this.lifecycle = lifecycle;
        this.orderQueries = orderQueries;
        this.approvalTokens = approvalTokens;
    }

    @Operation(summary = "Check out the cart: pay now (CLOSED) or place the order unpaid (OPEN)",
            description = "Order, lines and payments are written in one transaction. 201 when created; repeating "
                    + "a request with the same clientRequestId returns the original order with 200.")
    @PostMapping("/orders")
    ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        CheckoutResult result = checkoutService.checkout(request, Terminal.of(jwt));
        URI location = URI.create("/api/v1/orders/" + result.order().id());
        return result.created()
                ? ResponseEntity.created(location).body(result.order())
                : ResponseEntity.ok().location(location).body(result.order());
    }

    @Operation(summary = "Price the cart without saving it",
            description = "Returns the totals, any promotion problems that would block payment, and the "
                    + "member's points preview. Payments in the request are ignored. editing: the placed order "
                    + "the cart is editing, if any.")
    @PostMapping("/orders/quote")
    QuoteResponse quote(@Valid @RequestBody CheckoutRequest request, @RequestParam(required = false) Long editing,
                        @AuthenticationPrincipal Jwt jwt) {
        return checkoutService.quote(request, Terminal.of(jwt), editing);
    }

    @Operation(summary = "A placed (unpaid) order as a cart, for \"Edit Order\"",
            description = "At today's prices. 422 once the order has been paid or voided.")
    @GetMapping("/orders/{orderId}/cart")
    EditableOrder editableOrder(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        return checkoutService.editable(orderId, Terminal.of(jwt).outletId());
    }

    @Operation(summary = "Save an edited placed order: place it again (PLACE_ORDER) or pay it (PAY)",
            description = "Replaces its items, customer, discounts and note; it keeps its number. Paying is "
                    + "idempotent per clientRequestId.")
    @PutMapping("/orders/{orderId}")
    OrderResponse editOrder(@PathVariable Long orderId, @Valid @RequestBody CheckoutRequest request,
                            @AuthenticationPrincipal Jwt jwt) {
        return checkoutService.edit(orderId, request, Terminal.of(jwt));
    }

    @Operation(summary = "Past Orders at the terminal's outlet: one status tab, newest first",
            description = "q matches an order number (\"355\" or \"R355\"), a receipt-code prefix or part of "
                    + "a payment reference.")
    @GetMapping("/orders")
    PageResponse<OrderSummary> searchOrders(@RequestParam OrderStatus status,
                                            @RequestParam(required = false) @Size(max = 40) String q,
                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                            @AuthenticationPrincipal Jwt jwt) {
        return orderQueries.search(Terminal.of(jwt).outletId(), status, q, page, size);
    }

    @Operation(summary = "An order with its lines, payments, and any void or refund")
    @GetMapping("/orders/{orderId}")
    OrderResponse getOrder(@PathVariable Long orderId, @AuthenticationPrincipal Jwt jwt) {
        return orderQueries.getOrder(orderId, Terminal.of(jwt).outletId());
    }

    @Operation(summary = "Pay a placed (OPEN) order in full",
            description = "Repeating a request with the same clientRequestId returns the paid order.")
    @PostMapping("/orders/{orderId}/payments")
    OrderResponse payOrder(@PathVariable Long orderId, @Valid @RequestBody PayOrderRequest request,
                           @AuthenticationPrincipal Jwt jwt) {
        return lifecycle.pay(orderId, request, Terminal.of(jwt));
    }

    @Operation(summary = "Void an unpaid order (manager only)")
    @PreAuthorize("hasRole('MANAGER')")
    @PostMapping("/orders/{orderId}/void")
    OrderResponse voidOrder(@PathVariable Long orderId, @Valid @RequestBody ReasonRequest request,
                            @AuthenticationPrincipal Jwt jwt) {
        approvalTokens.useOnce(jwt, ApprovalOperation.VOID);
        return lifecycle.voidOrder(orderId, request.reason(), Terminal.of(jwt));
    }

    @Operation(summary = "Refund a paid order in full to its original payment methods (manager only)")
    @PreAuthorize("hasRole('MANAGER')")
    @PostMapping("/orders/{orderId}/refund")
    OrderResponse refundOrder(@PathVariable Long orderId, @Valid @RequestBody ReasonRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        approvalTokens.useOnce(jwt, ApprovalOperation.REFUND);
        return lifecycle.refund(orderId, request.reason(), Terminal.of(jwt));
    }

    @Operation(summary = "Payment methods offered on the payment screen")
    @GetMapping("/payment-methods")
    List<PaymentMethodResponse> getPaymentMethods() {
        return orderQueries.getActivePaymentMethods();
    }

}
