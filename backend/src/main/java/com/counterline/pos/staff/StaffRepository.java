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

    /** SELECT … FOR UPDATE: serialises per-staff state changes such as clocking in/out. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Staff s where s.id = :id")
    Optional<Staff> lockById(@Param("id") Long id);
}
