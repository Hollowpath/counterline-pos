package com.counterline.pos.timecards;

import com.counterline.pos.auth.LoginAttemptLimiter;
import com.counterline.pos.auth.LoginAttemptLimiter.Attempt;
import com.counterline.pos.auth.LoginAttemptLimiter.Limit;
import com.counterline.pos.auth.PinHasher;
import com.counterline.pos.auth.PinPad;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.timecards.TimecardEvent.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * Clock In/Out at a logged-in terminal: a staff member enters their PIN, sees whether they are
 * clocked in, and taps Clock In or Clock Out themselves (nothing is recorded by the PIN alone). The PIN is checked outside
 * any transaction (the login throttle must be, see {@link LoginAttemptLimiter}); the clocking itself is one transaction.
 */
@Service
public class TimecardService {

    private static final Logger log = LoggerFactory.getLogger(TimecardService.class);

    private final StaffRepository staffRepository;
    private final OutletService outletService;
    private final TimecardEventRepository eventRepository;
    private final PinHasher pinHasher;
    private final LoginAttemptLimiter limiter;
    private final DatabaseClock databaseClock;
    private final TransactionTemplate transaction;

    TimecardService(StaffRepository staffRepository, OutletService outletService, TimecardEventRepository eventRepository,
                    PinHasher pinHasher, LoginAttemptLimiter limiter, DatabaseClock databaseClock, PlatformTransactionManager transactions) {
        this.transaction = new TransactionTemplate(transactions);
        this.staffRepository = staffRepository;
        this.outletService = outletService;
        this.eventRepository = eventRepository;
        this.pinHasher = pinHasher;
        this.limiter = limiter;
        this.databaseClock = databaseClock;
    }

    /** {@code clockedIn}: whether their last event was IN, at {@code since} (null if never clocked). */
    public record ClockStatus(String staffName, boolean clockedIn, Instant since) {
    }

    public record ClockResult(String staffName, Type type, Instant at) {
    }

    /** Whether the staff member with this PIN is clocked in; records nothing. */
    public ClockStatus status(String pin, Long outletId, String client) {
        Staff staff = staffAt(pin, outletId, client);
        Optional<TimecardEvent> last = eventRepository.findFirstByStaffIdOrderByIdDesc(staff.getId());
        return new ClockStatus(staff.getFullName(), last.map(event -> event.getType() == Type.IN).orElse(false),
                last.map(TimecardEvent::getOccurredAt).orElse(null));
    }

    /**
     * Clocks the staff member in or out, as they chose. Choosing what they already are (clocking in
     * twice, e.g. from two tills) is refused.
     */
    public ClockResult clock(String pin, Type type, Long outletId, String client) {
        Long staffId = staffAt(pin, outletId, client).getId();
        String pinHmac = pinHasher.hash(pin);
        return transaction.execute(status -> recordClock(staffId, pinHmac, type, outletId));
    }

    /**
     * The clocking itself, in the caller's transaction. {@code pinHmac}: the PIN that was checked, which must still be the
     * person's once their row is locked (a reset since then, like a deactivation or a move, makes the attempt fail).
     */
    ClockResult recordClock(Long staffId, String pinHmac, Type type, Long outletId) {
        java.util.Objects.requireNonNull(pinHmac, "The checked PIN is needed to record a clock event");

        // A shift can only start at an open outlet, and the outlet stays open while it does (deactivating an
        // outlet waits for this transaction). Outlet lock first, then the person's: the order everywhere.
        if (type == Type.IN) {
            try {
                outletService.getActiveHeldOpen(outletId);
            } catch (ResourceNotFoundException e) {
                throw new BusinessRuleException("This outlet is closed");
            }
        }

        // Row lock: two quick submissions for the same person are serialised, so the second sees
        // the first one's event.
        Staff staff = staffRepository.lockById(staffId).orElseThrow();
        // The PIN was checked before this transaction. Deactivating a person, moving them to another outlet and resetting
        // their PIN all take this same row lock, so what is read here is what the change left: if they are no longer this
        // outlet's active staff, or the PIN that was checked is no longer theirs, it no longer stands, and nothing is
        // recorded (answered like a wrong PIN, which never confirms whose it is).
        if (!staff.isActive() || !staff.worksAt(outletId) || !staff.hasPinHmac(pinHmac)) {
            throw new BusinessRuleException("Invalid PIN");
        }
        Optional<TimecardEvent> latest = eventRepository.findFirstByStaffIdOrderByIdDesc(staff.getId());
        boolean clockedIn = latest.map(last -> last.getType() == Type.IN).orElse(false);
        if (clockedIn == (type == Type.IN)) {
            throw new BusinessRuleException(staff.getFullName() + " is already clocked " + (clockedIn ? "in" : "out"));
        }

        // The event is dated by the database's clock, read now that the person's row is locked: the one clock every server
        // shares, in the order the events are recorded. Their state is decided by event id, so a previous event dated ahead
        // of it (a bad clock or import long ago) changes nothing here: it is logged for someone to look at, and the time is
        // never copied from it, so the person is not locked out and their later events are dated truly.
        Instant at = databaseClock.now();
        if (latest.isPresent() && latest.get().getOccurredAt().isAfter(at)) {
            log.warn("Staff {} clocked {} at {}, before their previous event ({}), which is dated ahead of the database's time",
                    staff.getId(), type, at, latest.get().getOccurredAt());
        }
        TimecardEvent event = eventRepository.save(new TimecardEvent(outletId, staff, type, at));
        log.info("Staff {} clocked {} at outlet {}", staff.getId(), type, outletId);
        return new ClockResult(staff.getFullName(), event.getType(), event.getOccurredAt());
    }

    /**
     * Throttled like the PIN login (per outlet, on its own), so the clock pad cannot be used to guess
     * PINs. A wrong PIN is a 422, not a 401, because the terminal's own session is fine.
     */
    private Staff staffAt(String pin, Long outletId, String client) {
        try (Attempt attempt = limiter.begin(new Limit(PinPad.CLOCK.key(outletId), limiter.maxFailures()))) {
            // Another outlet's staff member is answered like a wrong PIN: the pad never confirms whose a PIN is.
            Staff staff = staffRepository.findByPinHmacAndActiveTrue(pinHasher.hash(pin))
                    .filter(found -> found.worksAt(outletId))
                    .orElse(null);
            if (staff == null) {
                log.warn("Wrong Clock In/Out PIN at outlet {} from {}", outletId, client);
                throw new BusinessRuleException(limiter.withTriesLeft("Invalid PIN", attempt.failed()));
            }
            attempt.succeeded();
            return staff;
        }
    }
}
