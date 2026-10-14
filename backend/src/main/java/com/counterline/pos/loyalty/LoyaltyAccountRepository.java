package com.counterline.pos.loyalty;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    /** Creates the member's account on first use; a no-op when it exists. */
    @Modifying
    @Query(value = """
            INSERT INTO loyalty_account (customer_id, points_balance, updated_at) VALUES (:customerId, 0, :now)
            ON CONFLICT (customer_id) DO NOTHING
            """, nativeQuery = true)
    void ensureExists(@Param("customerId") Long customerId, @Param("now") Instant now);

    /** SELECT … FOR UPDATE: held until the sale's transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LoyaltyAccount a where a.customerId = :customerId")
    Optional<LoyaltyAccount> lock(@Param("customerId") Long customerId);
}
