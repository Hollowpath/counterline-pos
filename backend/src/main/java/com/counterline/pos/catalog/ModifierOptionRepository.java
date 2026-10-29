package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface ModifierOptionRepository extends JpaRepository<ModifierOption, Long> {

    /** Every option of a group, active or not, in display order. */
    @Query("select o from ModifierOption o where o.group.id = :groupId order by o.sortOrder")
    List<ModifierOption> findByGroup(@Param("groupId") Long groupId);

    @Query("select coalesce(max(o.sortOrder), 0) from ModifierOption o where o.group.id = :groupId")
    int maxSortOrder(@Param("groupId") Long groupId);
}
