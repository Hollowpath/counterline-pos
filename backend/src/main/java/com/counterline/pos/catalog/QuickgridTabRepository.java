package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface QuickgridTabRepository extends JpaRepository<QuickgridTab, Long> {

    List<QuickgridTab> findAllByOrderBySortOrderAsc();
}
