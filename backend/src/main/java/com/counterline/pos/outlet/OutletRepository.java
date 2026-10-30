package com.counterline.pos.outlet;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface OutletRepository extends JpaRepository<Outlet, Long> {

    List<Outlet> findByActiveTrueOrderByName();

    Optional<Outlet> findByIdAndActiveTrue(Long id);

    List<Outlet> findAllByOrderByCodeAsc();

    /** SELECT … FOR SHARE: many clock-ins at once, but none while the outlet is being deactivated. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select o from Outlet o where o.id = :id")
    Optional<Outlet> lockShared(@Param("id") Long id);

    /** SELECT … FOR UPDATE: the outlet's deactivation, which waits for clock-ins in progress and holds them off. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Outlet o where o.id = :id")
    Optional<Outlet> lockForUpdate(@Param("id") Long id);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
