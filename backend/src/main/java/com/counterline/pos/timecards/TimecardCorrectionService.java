package com.counterline.pos.timecards;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.timecards.TimecardEvent.Type;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

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
    private final Clock clock;

    TimecardCorrectionService(StaffRepository staffRepository, TimecardEventRepository eventRepository, Clock clock) {
        this.staffRepository = staffRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    public record CloseShift(@NotNull Instant outAt, @NotBlank @Size(max = 200) String reason) {
    }

    public record ClosedShift(String staffName, Instant inAt, Instant outAt, String correctedBy, String reason) {
    }

    /**
     * @param outletId the outlet the shift was started at
     * @throws ResourceNotFoundException (404) for an outlet the user may not see
     * @throws ConflictException (409) if the staff member has no open shift at that outlet
     * @throws BadRequestException (400) if the OUT is before the IN or in the future
     */
    @Transactional
    public ClosedShift close(DashboardUser user, Long outletId, Long staffId, CloseShift request, String correctedBy) {
        user.requireOutlet(outletId);
        String reason = request.reason().strip();
        if (reason.isEmpty()) {
            throw new BadRequestException("Give a reason for the correction");
        }
        // The same row lock as Clock In/Out: a correction and the person's own Clock Out cannot both win.
        Staff staff = staffRepository.lockById(staffId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff member " + staffId + " not found"));
        TimecardEvent last = eventRepository.findFirstByStaffIdOrderByOccurredAtDescIdDesc(staffId).orElse(null);
        if (last == null || last.getType() != Type.IN || !outletId.equals(last.getOutletId())) {
            throw new ConflictException(staff.getFullName() + " has no open shift at this outlet");
        }
        Instant now = clock.instant();
        if (request.outAt().isBefore(last.getOccurredAt())) {
            throw new BadRequestException("The clock-out cannot be before the clock-in");
        }
        if (request.outAt().isAfter(now)) {
            throw new BadRequestException("The clock-out cannot be in the future");
        }
        eventRepository.save(TimecardEvent.correctedOut(outletId, staff, request.outAt(), correctedBy, reason, now));
        log.info("Shift of staff {} at outlet {} closed at {} by {}", staffId, outletId, request.outAt(), correctedBy);
        return new ClosedShift(staff.getFullName(), last.getOccurredAt(), request.outAt(), correctedBy, reason);
    }
}
