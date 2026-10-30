package com.counterline.pos.customers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Searches cover active members; {@code includeInactive} (the dashboard) adds deactivated ones. Phone, email and
 * reference ID are unique across every member, deactivated included. LIKE patterns use '!' as the escape character.
 */
interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByIdAndDeactivatedAtIsNull(Long id);

    Page<Customer> findByDeactivatedAtIsNull(Pageable pageable);

    /** Case-insensitive "contains" (index: trigram on full_name). */
    @Query("select c from Customer c where (:includeInactive = true or c.deactivatedAt is null) and c.fullName ilike :pattern escape '!'")
    Page<Customer> searchByName(@Param("pattern") String pattern, @Param("includeInactive") boolean includeInactive, Pageable pageable);

    @Query("select c from Customer c where (:includeInactive = true or c.deactivatedAt is null) and c.phone like :pattern escape '!'")
    Page<Customer> searchByPhone(@Param("pattern") String pattern, @Param("includeInactive") boolean includeInactive, Pageable pageable);

    @Query("select c from Customer c where (:includeInactive = true or c.deactivatedAt is null) and c.email ilike :pattern escape '!'")
    Page<Customer> searchByEmail(@Param("pattern") String pattern, @Param("includeInactive") boolean includeInactive, Pageable pageable);

    @Query("select c from Customer c where (:includeInactive = true or c.deactivatedAt is null) and c.referenceId = :referenceId")
    Page<Customer> searchByReferenceId(@Param("referenceId") String referenceId, @Param("includeInactive") boolean includeInactive,
                                       Pageable pageable);

    /** The active member with this phone number (the display's Member Login). */
    Optional<Customer> findByPhoneAndDeactivatedAtIsNull(String phone);

    // Uniqueness checks among all members, to report which field clashes.

    Optional<Customer> findByPhone(String phone);

    Optional<Customer> findByEmail(String email);

    Optional<Customer> findByReferenceId(String referenceId);
}
