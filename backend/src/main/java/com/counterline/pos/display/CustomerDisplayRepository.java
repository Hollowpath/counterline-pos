package com.counterline.pos.display;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface CustomerDisplayRepository extends JpaRepository<CustomerDisplay, Long> {

    /** A display waiting to connect with this code (index: customer_display_waiting_code_uq). */
    Optional<CustomerDisplay> findByAccessCodeAndConnectedAtIsNullAndRevokedAtIsNull(String accessCode);

    boolean existsByAccessCodeAndConnectedAtIsNullAndRevokedAtIsNull(String accessCode);

    /**
     * The display waiting for this code, locked: of two displays entering it at once, one gets the row and the other
     * finds it taken. A code older than {@code cutoff} has expired.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select d from CustomerDisplay d
            where d.accessCode = :code and d.connectedAt is null and d.revokedAt is null and d.createdAt > :cutoff""")
    Optional<CustomerDisplay> lockWaiting(@Param("code") String code, @Param("cutoff") Instant cutoff);

    /** Codes still waiting to be entered at this outlet (not expired). */
    @Query("""
            select count(d) from CustomerDisplay d
            where d.outletId = :outletId and d.connectedAt is null and d.revokedAt is null and d.createdAt > :cutoff""")
    long countWaiting(@Param("outletId") Long outletId, @Param("cutoff") Instant cutoff);

    /** Codes nobody entered in time: gone, so their numbers can be used again. */
    @Modifying
    @Query("delete from CustomerDisplay d where d.connectedAt is null and d.revokedAt is null and d.createdAt <= :cutoff")
    int deleteExpiredWaiting(@Param("cutoff") Instant cutoff);

    /** Displays still connected, or waiting for their code, in outlet order (for the dashboard). */
    @Query("""
            select d from CustomerDisplay d
            where d.revokedAt is null and (d.connectedAt is not null or d.createdAt > :cutoff)
            order by d.outletId, d.id""")
    List<CustomerDisplay> findLive(@Param("cutoff") Instant cutoff);

    /** Held until the transaction ends: creating displays for one outlet one at a time (the cap is per outlet). */
    @Query(value = "select 1 from (select pg_advisory_xact_lock(:key)) as locked", nativeQuery = true)
    int lockCreation(@Param("key") long key);

    /** Changes from the POS and the display are serialised on the row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from CustomerDisplay d where d.id = :id")
    Optional<CustomerDisplay> lock(@Param("id") Long id);

    /** "Last seen" without bumping the version the display polls for. */
    @Modifying
    @Query("update CustomerDisplay d set d.displaySeenAt = :at where d.id = :id")
    void markSeen(@Param("id") Long id, @Param("at") Instant at);
}
