package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface ItemRepository extends JpaRepository<Item, Long> {

    /** The promotion is shown on each tile, so it is fetched with the items. */
    @EntityGraph(attributePaths = "promotion")
    List<Item> findByCategoryIdAndActiveTrueOrderBySortOrderAsc(Long categoryId);

    /** Checkout prices lines and applies promotions, so both are fetched with the items. */
    @EntityGraph(attributePaths = {"category", "promotion"})
    List<Item> findByIdInAndActiveTrue(Collection<Long> ids);

    List<Item> findByPromotionIdAndActiveTrueOrderBySortOrderAsc(Long promotionId);

    /** Whether any item on sale is a seasonal flavour on that day (both ends of its window included). */
    @org.springframework.data.jpa.repository.Query("""
            select count(i) > 0 from Item i
            where i.active = true and i.seasonal = true
              and (i.seasonalFrom is null or i.seasonalFrom <= :day)
              and (i.seasonalUntil is null or i.seasonalUntil >= :day)
            """)
    boolean existsSeasonalOn(@org.springframework.data.repository.query.Param("day") java.time.LocalDate day);

    /** Every item, active or not, for the dashboard: folders in till order (tab, then grid), off-grid folders last. */
    @org.springframework.data.jpa.repository.Query("""
            select i from Item i join fetch i.category c left join fetch c.tab t left join fetch i.promotion
            order by t.sortOrder nulls last, c.gridRow nulls last, c.gridCol nulls last, c.name, i.sortOrder
            """)
    List<Item> findAllInTillOrder();

    @org.springframework.data.jpa.repository.Query("select coalesce(max(i.sortOrder), 0) from Item i where i.category.id = :categoryId")
    int maxSortOrder(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);

    long countByCategoryId(Long categoryId);

    /** Every item of a folder, on sale or not, in display order. */
    List<Item> findByCategoryIdOrderBySortOrderAsc(Long categoryId);

    boolean existsByCategoryIdAndNameIgnoreCase(Long categoryId, String name);

    boolean existsByCategoryIdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);

    /**
     * SELECT … FOR UPDATE of one item: a sale that is saving a line of it (which holds a share of the row) is waited
     * for, so removing an item and selling it meet in order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    java.util.Optional<Item> lockById(@Param("id") Long id);
}
