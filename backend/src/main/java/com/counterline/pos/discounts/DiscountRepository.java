package com.counterline.pos.discounts;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface DiscountRepository extends JpaRepository<Discount, Long> {

    List<Discount> findByActiveTrueOrderBySortOrderAsc();

    List<Discount> findAllByOrderBySortOrderAsc();

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(d.sortOrder), 0) from Discount d")
    short maxSortOrder();
}
