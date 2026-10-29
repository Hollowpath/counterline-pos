package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Set;

interface ModifierGroupRepository extends JpaRepository<ModifierGroup, Long> {

    /** Groups of the given items with their options, in display order, in one query. */
    @Query("""
            select distinct g from ModifierGroup g
            left join fetch g.options
            where g.item.id in :itemIds
            order by g.sortOrder
            """)
    List<ModifierGroup> findWithOptionsByItemIds(@Param("itemIds") Collection<Long> itemIds);

    /** All groups of an item, in display order (options are read separately). */
    @Query("select g from ModifierGroup g where g.item.id = :itemId order by g.sortOrder")
    List<ModifierGroup> findByItemIdOrderBySortOrderAsc(@Param("itemId") Long itemId);

    @Query("select coalesce(max(g.sortOrder), 0) from ModifierGroup g where g.item.id = :itemId")
    int maxSortOrder(@Param("itemId") Long itemId);

    /** Which of these items open the item dialog (have at least one option group). */
    @Query("select distinct g.item.id from ModifierGroup g join g.options o where g.item.id in :itemIds and o.active = true")
    Set<Long> findItemIdsWithGroups(@Param("itemIds") Collection<Long> itemIds);
}
