package com.counterline.pos.timecards;

import com.counterline.pos.auth.LoginAttemptLimiter;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.PinPad;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.timecards.TimecardEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Clock In/Out at a logged-in terminal: a staff member enters their PIN, sees whether they are
 * clocked in, and taps Clock In or Clock Out themselves (nothing is recorded by the PIN alone).
 */
@Service
public class TimecardService {

    private static final Logger log = LoggerFactory.getLogger(TimecardService.class);

    private final StaffRepository staffRepository;
    private final TimecardEventRepository eventRepository;
    private final PinHasher pinHasher;
    private final LoginAttemptLimiter limiter;
    private final Clock clock;

    TimecardService(StaffRepository staffRepository, TimecardEventRepository eventRepository,
                    PinHasher pinHasher, LoginAttemptLimiter limiter, Clock clock) {
        this.staffRepository = staffRepository;
        this.eventRepository = eventRepository;
        this.pinHasher = pinHasher;
        this.limiter = limiter;
        this.clock = clock;
    }

    /** {@code clockedIn}: whether their last event was IN, at {@code since} (null if never clocked). */
    public record ClockStatus(String staffName, boolean clockedIn, Instant since) {
    }

    public record ClockResult(String staffName, Type type, Instant at) {
    }

    /** Whether the staff member with this PIN is clocked in; records nothing. */
    @Transactional(readOnly = true)
    public ClockStatus status(String pin, Long outletId, String client) {
        Staff staff = staffAt(pin, outletId, client);
        Optional<TimecardEvent> last = eventRepository.findFirstByStaffIdOrderByOccurredAtDescIdDesc(staff.getId());
        return new ClockStatus(staff.getFullName(), last.map(event -> event.getType() == Type.IN).orElse(false),
                last.map(TimecardEvent::getOccurredAt).orElse(null));
    }

    /**
     * Clocks the staff member in or out, as they chose. Choosing what they already are (clocking in
     * twice, e.g. from two tills) is refused.
     */
    @Transactional
    public ClockResult clock(String pin, Type type, Long outletId, String client) {
        Staff staff = staffAt(pin, outletId, client);

        // Row lock: two quick submissions for the same person are serialised, so the second sees
        // the first one's event.
        staffRepository.lockById(staff.getId());
        boolean clockedIn = eventRepository.findFirstByStaffIdOrderByOccurredAtDescIdDesc(staff.getId())
                .map(last -> last.getType() == Type.IN)
                .orElse(false);
        if (clockedIn == (type == Type.IN)) {
            throw new BusinessRuleException(staff.getFullName() + " is already clocked " + (clockedIn ? "in" : "out"));
        }

        TimecardEvent event = eventRepository.save(new TimecardEvent(outletId, staff, type, clock.instant()));
        log.info("Staff {} clocked {} at outlet {}", staff.getId(), type, outletId);
        return new ClockResult(staff.getFullName(), event.getType(), event.getOccurredAt());
    }

    /**
     * Throttled like the PIN login (per outlet, on its own), so the clock pad cannot be used to guess
     * PINs. A wrong PIN is a 422, not a 401, because the terminal's own session is fine.
     */
    private Staff staffAt(String pin, Long outletId, String client) {
        String pad = PinPad.CLOCK.key(outletId);
        limiter.checkAllowed(pad);
        Staff staff = staffRepository.findByPinHmacAndActiveTrue(pinHasher.hash(pin)).orElse(null);
        if (staff == null) {
            log.warn("Wrong Clock In/Out PIN at outlet {} from {}", outletId, client);
            throw new BusinessRuleException(limiter.withTriesLeft("Invalid PIN", limiter.recordFailure(pad)));
        }
        limiter.recordSuccess(pad);
        if (!staff.worksAt(outletId)) {
            throw new BusinessRuleException(staff.getFullName() + " works at another outlet");
        }
        return staff;
    }
}
