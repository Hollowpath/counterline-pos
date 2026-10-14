package com.counterline.pos.vouchers;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

interface VoucherRepository extends JpaRepository<Voucher, Long> {

    @EntityGraph(attributePaths = {"batch", "batch.discount"})
    Optional<Voucher> findByCode(String code);

    /** Locked while a sale uses it, so two tills can't use the same voucher at once. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from Voucher v join fetch v.batch b join fetch b.discount where v.code = :code")
    Optional<Voucher> lockByCode(@Param("code") String code);

    List<Voucher> findByUsedOrderId(Long orderId);

    List<Voucher> findByBatchIdOrderByCode(Long batchId);

    @Query("select v.code from Voucher v where v.code in :codes")
    List<String> findExistingCodes(@Param("codes") Collection<String> codes);

    @Query("""
            select v.batch.id as batchId, count(v) as issued,
                   sum(case when v.usedOrderId is not null then 1 else 0 end) as used,
                   sum(case when v.cancelledAt is not null then 1 else 0 end) as cancelled
            from Voucher v group by v.batch.id
            """)
    List<BatchCounts> countByBatch();

    interface BatchCounts {
        Long getBatchId();

        long getIssued();

        long getUsed();

        long getCancelled();
    }
}
