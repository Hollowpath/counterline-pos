package com.counterline.pos.orders;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface PaymentMethodRepository extends JpaRepository<PaymentMethod, Long> {

    List<PaymentMethod> findByActiveTrueOrderBySortOrderAsc();
}
