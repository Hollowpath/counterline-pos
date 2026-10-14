package com.counterline.pos.register;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface RegisterSessionRepository extends JpaRepository<RegisterSession, Long> {

    /** The outlet's open session, if any (index: register_session_one_open_uq). */
    Optional<RegisterSession> findByOutletIdAndClosedAtIsNull(Long outletId);

    Optional<RegisterSession> findFirstByOutletIdAndClosedAtIsNotNullOrderByClosedAtDesc(Long outletId);
}
