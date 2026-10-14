package com.counterline.pos.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface MemberOwedItemRepository extends JpaRepository<MemberOwedItem, Long> {

    /** Still owed (index: member_owed_item_open_idx), oldest first. */
    @Query("""
            select o from MemberOwedItem o join fetch o.promotion
            where o.customerId = :customerId and o.quantityUsed < o.quantity
            order by o.createdAt, o.id
            """)
    List<MemberOwedItem> findOpen(@Param("customerId") Long customerId);

    /** Locked while a sale uses them, so two tills can't give the same owed item twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select o from MemberOwedItem o
            where o.customerId = :customerId and o.promotion.id = :promotionId and o.quantityUsed < o.quantity
            order by o.createdAt, o.id
            """)
    List<MemberOwedItem> lockOpen(@Param("customerId") Long customerId, @Param("promotionId") Long promotionId);
}
