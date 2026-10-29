package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

interface QuickgridTabRepository extends JpaRepository<QuickgridTab, Long> {

    List<QuickgridTab> findAllByOrderBySortOrderAsc();

    Optional<QuickgridTab> findByNameIgnoreCase(String name);

    @Query("select coalesce(max(t.sortOrder), 0) from QuickgridTab t")
    int maxSortOrder();
}
