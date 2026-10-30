package com.counterline.pos.orders;

import com.counterline.pos.promotions.Promotion;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    Optional<SaleOrder> findByClientRequestId(UUID clientRequestId);

    Optional<SaleOrder> findByIdAndOutletId(Long id, Long outletId);

    /** For editing a placed order: locked, so two tills can't change or pay it at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from SaleOrder o where o.id = :id and o.outletId = :outletId")
    Optional<SaleOrder> lockByIdAndOutletId(@Param("id") Long id, @Param("outletId") Long outletId);

    @Query(value = "select nextval('sale_order_number_seq')", nativeQuery = true)
    long nextOrderNumber();

    // Past Orders: one outlet's orders, one status tab at a time (index: outlet_id, status, created_at desc).
    // The customer is fetched with the page because every row shows the customer tag.

    @EntityGraph(attributePaths = "customer")
    Page<SaleOrder> findByOutletIdAndStatus(Long outletId, OrderStatus status, Pageable pageable);

    /**
     * Search: the order number, a receipt-code prefix (index: receipt_code varchar_pattern_ops) or
     * one of the given orders (matched by payment reference). {@code orderNumber} is -1 and
     * {@code orderIds} is [-1] when that part does not apply.
     */
    @EntityGraph(attributePaths = "customer")
    @Query("""
            select o from SaleOrder o
            where o.outletId = :outletId and o.status = :status
              and (o.orderNumber = :orderNumber or o.receiptCode like :receiptPrefix or o.id in :orderIds)
            """)
    Page<SaleOrder> search(@Param("outletId") Long outletId, @Param("status") OrderStatus status,
                           @Param("orderNumber") long orderNumber,
                           @Param("receiptPrefix") String receiptPrefix, @Param("orderIds") List<Long> orderIds,
                           Pageable pageable);

    /**
     * Orders with a payment reference containing the term, e.g. the card's last digits
     * (index: lower(reference) trigram). {@code pattern} is "%term%" with LIKE wildcards escaped by '!'.
     */
    @Query(value = """
            select distinct p.order_id from payment p
            where lower(p.reference) like :pattern escape '!'
            limit 500
            """, nativeQuery = true)
    List<Long> findOrderIdsByPaymentReference(@Param("pattern") String pattern);

    /**
     * Whether the member has had this promotion on an order that was paid and not refunded, other than
     * {@code exceptOrderId} (the order being edited or paid, or 0). For once-only rewards (Registration
     * Reward). Placed orders do not count: two unpaid orders holding the same reward would otherwise block each
     * other's payment; the first to be paid takes it (the member's account is locked while that is checked).
     */
    @Query("""
            select count(p) > 0 from SaleOrderPromotion p join p.order o
            where o.customer.id = :customerId and p.promotion.id = :promotionId and o.id <> :exceptOrderId
              and o.status = com.counterline.pos.orders.OrderStatus.CLOSED and o.refundedAt is null
            """)
    boolean memberHasHadPromotion(@Param("customerId") Long customerId,
                                  @Param("promotionId") Long promotionId,
                                  @Param("exceptOrderId") long exceptOrderId);

    /** As {@link #memberHasHadPromotion}, at one outlet (the Google Review reward, once per outlet). */
    @Query("""
            select count(p) > 0 from SaleOrderPromotion p join p.order o
            where o.customer.id = :customerId and p.promotion.id = :promotionId and o.outletId = :outletId
              and o.id <> :exceptOrderId
              and o.status = com.counterline.pos.orders.OrderStatus.CLOSED and o.refundedAt is null
            """)
    boolean memberHasHadPromotionAtOutlet(@Param("customerId") Long customerId,
                                          @Param("promotionId") Long promotionId,
                                          @Param("outletId") Long outletId,
                                          @Param("exceptOrderId") long exceptOrderId);

    /** Customer analytics totals over the customer's paid, non-refunded orders (index: customer_id). */
    @Query("""
            select new com.counterline.pos.orders.CustomerOrderStats(count(o), coalesce(sum(o.total), 0), max(o.createdAt))
            from SaleOrder o
            where o.customer.id = :customerId and o.status = :status and o.refundedAt is null
            """)
    CustomerOrderStats customerStats(@Param("customerId") Long customerId, @Param("status") OrderStatus status);
}
