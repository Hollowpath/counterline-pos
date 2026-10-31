package com.counterline.pos.timecards;

import com.counterline.pos.auth.Actor;
import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.outlet.OutletService;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.timecards.TimecardEvent.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Closes a shift that was left open (a forgotten Clock Out, or a staff member who can no longer sign
 * in). The IN stays as it was; the correction is a separate OUT event that records who made it and why.
 * Head office may do this for any outlet, a manager for their own.
 */
@Service
public class TimecardCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(TimecardCorrectionService.class);

    private final StaffRepository staffRepository;
    private final TimecardEventRepository eventRepository;
    private final DatabaseClock databaseClock;
    private final OutletService outletService;
    private final ZoneId zone;

    TimecardCorrectionService(StaffRepository staffRepository, TimecardEventRepository eventRepository,
                              DatabaseClock databaseClock, OutletService outletService, OutletProperties outlet) {
        this.staffRepository = staffRepository;
        this.eventRepository = eventRepository;
        this.databaseClock = databaseClock;
        this.outletService = outletService;
        this.zone = outlet.timeZone();
    }

    /**
     * The clock-out, as exactly one of: {@code outAtLocal}, the wall-clock time as the person entering it reads it
     * (read in the business time zone, never the browser's), or {@code outAt}, an instant.
     */
    public record CloseShift(Instant outAt, LocalDateTime outAtLocal, @NotBlank @Size(max = 200) String reason) {

        /**
         * The instant of the clock-out. A local time that does not exist in the zone (the clocks went forward over it) or
         * happens twice (they went back) is refused, not quietly moved to one of its readings.
         *
         * <p>Known limit, only in a zone with daylight saving (the default zone has none): the dashboard sends a local time,
         * so it cannot choose between the two instants of a repeated hour; a clock-out that really fell in it can only be
         * entered through the API with the exact instant ({@code outAt}).
         */
        Instant clockOut(ZoneId zone) {
            if ((outAt == null) == (outAtLocal == null)) {
                throw new BadRequestException("Give the time of the clock-out");
            }
            if (outAt != null) {
                return outAt;
            }
            int readings = zone.getRules().getValidOffsets(outAtLocal).size();
            if (readings == 0) {
                throw new BadRequestException("That time does not exist in " + zone.getId() + " (the clocks went forward). Choose another time.");
            }
            if (readings > 1) {
                throw new BadRequestException("That time happens twice in " + zone.getId() + " (the clocks went back). Choose a time outside that hour.");
            }
            return outAtLocal.atZone(zone).toInstant();
        }
    }

    private static final String NO_OPEN_SHIFT = "There is no open shift for that staff member at this outlet";

    /** The staff member's latest event if it is a clock-in made at {@code outletId}: their open shift there; else null. */
    private TimecardEvent openShiftAt(Long staffId, Long outletId) {
        return eventRepository.findFirstByStaffIdOrderByIdDesc(staffId)
                .filter(event -> event.getType() == Type.IN && outletId.equals(event.getOutletId()))
                .orElse(null);
    }

    public record ClosedShift(String staffName, Instant inAt, Instant outAt, String correctedBy, String reason) {
    }

    /**
     * @param actor who made the correction: kept with the event as a stable type and id, and as the name to show in reports
     * @param outletId the outlet the shift was started at
     * @throws ResourceNotFoundException (404) for an outlet the user may not see, or that does not exist
     * @throws ConflictException (409) if there is no open shift for that staff member at that outlet: one answer, whether
     *         they are someone else's staff, have no open shift, or do not exist, so the dashboard cannot be used to find out
     *         who works where
     * @throws BadRequestException (400) if the OUT is before the IN or in the future; when the IN itself is dated ahead of
     *         the database's time, before the staff member's latest earlier event that is not itself dated ahead instead
     */
    @Transactional
    public ClosedShift close(DashboardUser user, Long outletId, Long staffId, CloseShift request, Actor actor) {
        user.requireOutlet(outletId);
        // An outlet that does not exist is a 404, as in the reports (a closed one is found: its shifts can still be closed).
        // This comes after the permission check, so a manager is told the same about every outlet that is not theirs.
        outletService.findAny(outletId);
        // The request itself is judged before anything is locked or read.
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new BadRequestException("Give a reason for the correction");
        }
        Instant outAt = request.clockOut(zone);
        // Only a staff member with an open shift at this outlet is looked at further, and nobody else is locked: anything
        // else is the same answer, so the staff of other outlets are neither named nor told apart from people who do not exist.
        if (openShiftAt(staffId, outletId) == null) {
            throw new ConflictException(NO_OPEN_SHIFT);
        }
        // The same row lock as Clock In/Out: a correction and the person's own Clock Out cannot both win. Looked at again under it.
        Staff staff = staffRepository.lockById(staffId).orElseThrow(() -> new ConflictException(NO_OPEN_SHIFT));
        TimecardEvent last = openShiftAt(staffId, outletId);
        if (last == null) {
            throw new ConflictException(NO_OPEN_SHIFT);
        }
        // The same clock that dates the clock events.
        Instant now = databaseClock.now();
        if (last.getOccurredAt().isAfter(now)) {
            // A clock-in dated ahead of the database's time (a bad clock or import long ago) cannot have a clock-out after it
            // that is not in the future either: the shift is closed all the same, and it is logged for someone to look at.
            // The bound that still holds is the person's latest earlier event that is not itself dated ahead of the
            // database's time: the clock-out may not fall before it (none, no lower bound).
            log.warn("Shift of staff {} at outlet {} has a clock-in ({}) dated ahead of the database's time ({}); closing it at {}",
                    staffId, outletId, last.getOccurredAt(), now, outAt);
            eventRepository.findFirstByStaffIdAndIdLessThanAndOccurredAtLessThanEqualOrderByIdDesc(staffId, last.getId(), now)
                    .filter(previous -> outAt.isBefore(previous.getOccurredAt()))
                    .ifPresent(previous -> {
                        throw new BadRequestException("The clock-out cannot be before the staff member's previous event");
                    });
        } else if (outAt.isBefore(last.getOccurredAt())) {
            throw new BadRequestException("The clock-out cannot be before the clock-in");
        }
        if (outAt.isAfter(now)) {
            throw new BadRequestException("The clock-out cannot be in the future");
        }
        eventRepository.save(TimecardEvent.correctedOut(outletId, staff, outAt, actor, reason, now));
        log.info("Shift of staff {} at outlet {} closed at {} by {} {}", staffId, outletId, outAt, actor.type(), actor.id());
        return new ClosedShift(staff.getFullName(), last.getOccurredAt(), outAt, actor.name(), reason);
    }
}
