package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** Every item, active or not, for the dashboard: folders in till order (tab, then grid), off-grid folders last. */
    @org.springframework.data.jpa.repository.Query("""
            select i from Item i join fetch i.category c left join fetch c.tab t left join fetch i.promotion
            order by t.sortOrder nulls last, c.gridRow nulls last, c.gridCol nulls last, c.name, i.sortOrder
            """)
    List<Item> findAllInTillOrder();

    @org.springframework.data.jpa.repository.Query("select coalesce(max(i.sortOrder), 0) from Item i where i.category.id = :categoryId")
    int maxSortOrder(@org.springframework.data.repository.query.Param("categoryId") Long categoryId);

    boolean existsByCategoryIdAndNameIgnoreCase(Long categoryId, String name);

    boolean existsByCategoryIdAndNameIgnoreCaseAndIdNot(Long categoryId, String name, Long id);
}
