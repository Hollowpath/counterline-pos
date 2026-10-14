package com.counterline.pos.customers;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Searches cover active (not deleted) customers only. LIKE patterns use '!' as the escape character. */
interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByIdAndDeletedAtIsNull(Long id);

    Page<Customer> findByDeletedAtIsNull(Pageable pageable);

    /** Case-insensitive "contains" (index: trigram on full_name). */
    @Query("select c from Customer c where c.deletedAt is null and c.fullName ilike :pattern escape '!'")
    Page<Customer> searchByName(@Param("pattern") String pattern, Pageable pageable);

    @Query("select c from Customer c where c.deletedAt is null and c.phone like :pattern escape '!'")
    Page<Customer> searchByPhone(@Param("pattern") String pattern, Pageable pageable);

    @Query("select c from Customer c where c.deletedAt is null and c.email ilike :pattern escape '!'")
    Page<Customer> searchByEmail(@Param("pattern") String pattern, Pageable pageable);

    Page<Customer> findByReferenceIdAndDeletedAtIsNull(String referenceId, Pageable pageable);

    // Uniqueness checks among active customers, to report which field clashes.

    Optional<Customer> findByPhoneAndDeletedAtIsNull(String phone);

    Optional<Customer> findByEmailAndDeletedAtIsNull(String email);

    Optional<Customer> findByReferenceIdAndDeletedAtIsNull(String referenceId);
}
