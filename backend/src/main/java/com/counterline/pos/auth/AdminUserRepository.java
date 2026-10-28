package com.counterline.pos.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    Optional<AdminUser> findByEmailAndActiveTrue(String email);

    Optional<AdminUser> findByIdAndActiveTrue(Long id);

    boolean existsByEmail(String email);

    /** Includes deactivated accounts: their e-mail stays reserved by the unique constraint. */
    Optional<AdminUser> findByEmail(String email);
}
