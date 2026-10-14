package com.counterline.pos.display;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface CustomerDisplayRepository extends JpaRepository<CustomerDisplay, Long> {

    /** A display waiting to connect with this code (index: customer_display_waiting_code_uq). */
    Optional<CustomerDisplay> findByAccessCodeAndConnectedAtIsNullAndRevokedAtIsNull(String accessCode);

    boolean existsByAccessCodeAndConnectedAtIsNullAndRevokedAtIsNull(String accessCode);

    /** Changes from the POS and the display are serialised on the row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CustomerDisplay d where d.id = :id")
    Optional<CustomerDisplay> lock(@Param("id") Long id);

    /** "Last seen" without bumping the version the display polls for. */
    @Modifying
    @Query("update CustomerDisplay d set d.displaySeenAt = :at where d.id = :id")
    void markSeen(@Param("id") Long id, @Param("at") Instant at);
}
