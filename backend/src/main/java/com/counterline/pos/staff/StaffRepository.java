package com.counterline.pos.staff;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, Long> {

    Optional<Staff> findByPinHmacAndActiveTrue(String pinHmac);

    Optional<Staff> findByIdAndActiveTrue(Long id);

    Optional<Staff> findByEmailAndActiveTrue(String email);

    /** Includes inactive staff: their PIN and email stay reserved by the unique constraints. */
    Optional<Staff> findByPinHmac(String pinHmac);

    Optional<Staff> findByEmail(String email);

    /**
     * Ends the sessions of all of an outlet's staff (when the outlet is closed). {@code versioned}: it also
     * raises each row's optimistic version, so an edit read before this cannot write the old session version back.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("update versioned Staff s set s.authSessionVersion = s.authSessionVersion + 1 where s.outletId = :outletId")
    int revokeAuthSessions(@Param("outletId") Long outletId);

    /** Active managers of an outlet other than {@code exceptId}: the last one may not be removed. */
    long countByOutletIdAndRoleAndActiveTrueAndIdNot(Long outletId, StaffRole role, Long exceptId);

    java.util.List<Staff> findAllByOrderByFullNameAscIdAsc();

    java.util.List<Staff> findByOutletIdOrderByFullNameAscIdAsc(Long outletId);

    /** SELECT … FOR UPDATE: serialises per-staff state changes such as clocking in/out. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Staff s where s.id = :id")
    Optional<Staff> lockById(@Param("id") Long id);
}
