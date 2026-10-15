package com.counterline.pos.orders;

import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.PageResponse;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.orders.OrderDtos.CustomerAnalytics;
import com.counterline.pos.orders.OrderDtos.OrderResponse;
import com.counterline.pos.orders.OrderDtos.OrderSummary;
import com.counterline.pos.orders.OrderDtos.PaymentMethodResponse;
import com.counterline.pos.orders.OrderDtos.TopItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class OrderQueryService {

    /** "355" or "R355" is an order number. */
    private static final Pattern ORDER_NUMBER = Pattern.compile("^R?(\\d{1,15})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIPT_PREFIX = Pattern.compile("^[A-Za-z0-9]+$");
    /** Shorter terms would match too many payment references to be useful. */
    private static final int MIN_REFERENCE_TERM = 3;
    private static final long NO_ORDER_NUMBER = -1;
    private static final List<Long> NO_ORDER_IDS = List.of(-1L);
    private static final int ITEM_SUMMARY_MAX_LENGTH = 60;
    private static final int TOP_ITEMS = 5;

    private final SaleOrderRepository orderRepository;
    private final SaleOrderLineRepository lineRepository;
    private final PaymentMethodRepository paymentMethodRepository;

    OrderQueryService(SaleOrderRepository orderRepository, SaleOrderLineRepository lineRepository,
                      PaymentMethodRepository paymentMethodRepository) {
        this.orderRepository = orderRepository;
        this.lineRepository = lineRepository;
        this.paymentMethodRepository = paymentMethodRepository;
    }

    /** One of the outlet's orders; another outlet's is not found. */
    public OrderResponse getOrder(Long orderId, Long outletId) {
        return orderRepository.findByIdAndOutletId(orderId, outletId)
                .map(OrderResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + orderId + " not found"));
    }

    /**
     * The order a retried checkout already recorded. Only the outlet's own: a request id from another
     * outlet's order is refused (the ids are random, so that is a misuse, not a retry).
     */
    public Optional<OrderResponse> findByClientRequestId(UUID clientRequestId, Long outletId) {
        return orderRepository.findByClientRequestId(clientRequestId).map(order -> {
            if (!outletId.equals(order.getOutletId())) {
                throw new ConflictException("Request id already used");
            }
            return OrderResponse.from(order);
        });
    }

    /** Past Orders: the outlet's orders in one status tab, newest first, optionally filtered by a search term. */
    public PageResponse<OrderSummary> search(Long outletId, OrderStatus status, String query, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        String term = query == null ? "" : query.strip();

        Page<SaleOrder> orders = term.isEmpty()
                ? orderRepository.findByOutletIdAndStatus(outletId, status, pageable)
                : searchByTerm(outletId, status, term, pageable);

        Map<Long, String> itemSummaries = itemSummaries(orders.getContent());
        return PageResponse.from(orders, order -> new OrderSummary(order.getId(), order.getDisplayNumber(),
                order.getReceiptCode(), order.getStatus(), order.isRefunded(), order.getTotal(),
                itemSummaries.getOrDefault(order.getId(), ""),
                order.getCustomer() == null ? null : order.getCustomer().getFullName(),
                order.getCreatedAt()));
    }

    /**
     * A term matches the order number ("355", "R355"), the start of the receipt code, or part of a
     * payment reference ("0330" finds the card payment "4432-0330").
     */
    private Page<SaleOrder> searchByTerm(Long outletId, OrderStatus status, String term, PageRequest pageable) {
        Matcher number = ORDER_NUMBER.matcher(term);
        long orderNumber = number.matches() ? Long.parseLong(number.group(1)) : NO_ORDER_NUMBER;
        // Receipt codes are letters and digits only, so other terms cannot match one.
        String receiptPrefix = RECEIPT_PREFIX.matcher(term).matches() ? term.toUpperCase(Locale.ROOT) + "%" : "";
        List<Long> byReference = term.length() < MIN_REFERENCE_TERM ? List.of()
                : orderRepository.findOrderIdsByPaymentReference("%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%");
        return orderRepository.search(outletId, status, orderNumber, receiptPrefix,
                byReference.isEmpty() ? NO_ORDER_IDS : byReference, pageable);
    }

    private static String escapeLike(String text) {
        return text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /** Customer Analytics: totals and top 5 items over the customer's paid, non-refunded orders. */
    public CustomerAnalytics customerAnalytics(Long customerId) {
        CustomerOrderStats stats = orderRepository.customerStats(customerId, OrderStatus.CLOSED);
        BigDecimal average = stats.orderCount() == 0 ? BigDecimal.ZERO.setScale(2)
                : stats.orderTotal().divide(BigDecimal.valueOf(stats.orderCount()), 2, RoundingMode.HALF_UP);
        List<TopItem> topItems = lineRepository
                .mostOrderedItems(customerId, OrderStatus.CLOSED, PageRequest.of(0, TOP_ITEMS))
                .stream()
                .map(item -> new TopItem(item.itemName(), item.quantity()))
                .toList();
        return new CustomerAnalytics(stats.orderCount(), stats.orderTotal(), average, stats.lastOrderAt(), topItems);
    }

    public List<PaymentMethodResponse> getActivePaymentMethods() {
        return paymentMethodRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .map(method -> new PaymentMethodResponse(method.getId(), method.getName(), method.getKind()))
                .toList();
    }

    /** "Tin Caramel, Pouch Cheese, ..." per order, from one query for the whole page. */
    private Map<Long, String> itemSummaries(List<SaleOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = orders.stream().map(SaleOrder::getId).toList();
        return lineRepository.findByOrderIds(ids).stream()
                .collect(Collectors.groupingBy(SaleOrderLine::getOrderId,
                        Collectors.collectingAndThen(
                                Collectors.mapping(SaleOrderLine::getItemName, Collectors.joining(", ")),
                                OrderQueryService::abbreviate)));
    }

    private static String abbreviate(String text) {
        return text.length() <= ITEM_SUMMARY_MAX_LENGTH ? text : text.substring(0, ITEM_SUMMARY_MAX_LENGTH - 1) + "…";
    }
}
