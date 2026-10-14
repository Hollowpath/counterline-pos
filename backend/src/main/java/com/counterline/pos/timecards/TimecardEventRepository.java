package com.counterline.pos.timecards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface TimecardEventRepository extends JpaRepository<TimecardEvent, Long> {

    /** The staff member's latest event (index: staff_id, occurred_at desc). */
    Optional<TimecardEvent> findFirstByStaffIdOrderByOccurredAtDescIdDesc(Long staffId);
}
