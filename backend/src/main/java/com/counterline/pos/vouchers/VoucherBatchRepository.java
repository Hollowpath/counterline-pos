package com.counterline.pos.vouchers;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface VoucherBatchRepository extends JpaRepository<VoucherBatch, Long> {

    @EntityGraph(attributePaths = "discount")
    List<VoucherBatch> findAllByOrderByCreatedAtDesc();
}
