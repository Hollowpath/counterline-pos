package com.counterline.pos.orders;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface SaleOrderLineRepository extends JpaRepository<SaleOrderLine, Long> {

    /** Lines of a page of orders in one query (index: order_id, line_number). */
    @Query("select l from SaleOrderLine l where l.order.id in :orderIds order by l.lineNumber")
    List<SaleOrderLine> findByOrderIds(@Param("orderIds") Collection<Long> orderIds);

    /**
     * A customer's most ordered items (paid, non-refunded orders), by total quantity. Grouped by
     * item so a renamed item stays one entry (max() then just picks one of its sold names).
     */
    @Query("""
            select new com.counterline.pos.orders.ItemQuantity(max(l.itemName), sum(l.quantity))
            from SaleOrderLine l
            where l.order.customer.id = :customerId and l.order.status = :status and l.order.refundedAt is null
            group by l.item.id
            order by sum(l.quantity) desc, max(l.itemName)
            """)
    List<ItemQuantity> mostOrderedItems(@Param("customerId") Long customerId, @Param("status") OrderStatus status,
                                        Pageable limit);
}
