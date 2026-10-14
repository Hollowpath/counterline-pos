package com.counterline.pos.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface TillDeviceRepository extends JpaRepository<TillDevice, Long> {

    Optional<TillDevice> findByKeyHash(String keyHash);

    /** The iPads in use (not reset) at every outlet, by outlet and in the order they were set up. */
    @Query("""
            select d from TillDevice d join fetch d.setUpBy
            where d.resetAt is null
            order by d.outletId, d.setUpAt
            """)
    List<TillDevice> findActive();

    /** The iPads in use at one outlet. */
    @Query("""
            select d from TillDevice d join fetch d.setUpBy
            where d.resetAt is null and d.outletId = :outletId
            order by d.setUpAt
            """)
    List<TillDevice> findActiveAt(@Param("outletId") Long outletId);

    /** How many iPads the outlet has ever had, so new ones are numbered on ("iPad 3"). */
    long countByOutletId(Long outletId);
}
