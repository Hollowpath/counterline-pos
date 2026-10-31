package com.counterline.pos.timecards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

interface TimecardEventRepository extends JpaRepository<TimecardEvent, Long> {

    /** The staff member's latest event: the last one recorded, by id (index: staff_id, id desc), not by the time it carries. */
    Optional<TimecardEvent> findFirstByStaffIdOrderByIdDesc(Long staffId);

    /**
     * The staff member's latest event recorded before {@code id} whose time is not after {@code now}: events dated ahead of
     * the database's time are skipped (index: staff_id, id desc).
     */
    Optional<TimecardEvent> findFirstByStaffIdAndIdLessThanAndOccurredAtLessThanEqualOrderByIdDesc(Long staffId, Long id, Instant now);
}
