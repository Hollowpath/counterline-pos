package com.counterline.pos.timecards;

import com.counterline.pos.auth.Actor;
import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.timecards.TimecardCorrectionService.CloseShift;
import com.counterline.pos.timecards.TimecardCorrectionService.ClosedShift;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearer")
class TimecardCorrectionController {

    private final TimecardCorrectionService corrections;

    TimecardCorrectionController(TimecardCorrectionService corrections) {
        this.corrections = corrections;
    }

    @Operation(summary = "Dashboard: close an open shift with a clock-out the staff member did not make",
            description = "Head office for any outlet, a manager for their own. The clock-out is between the clock-in and now, "
                    + "and a reason is required; the clock-in is left as it was and the correction is recorded in the timecards report. "
                    + "There is one answer, 409, for 'no open shift for that staff member at this outlet', whoever they are. "
                    + "In a time zone with daylight saving (not the default one) a local time in the repeated hour is refused and the "
                    + "dashboard cannot choose between its two instants: send the exact instant (outAt) through the API instead.")
    @PostMapping("/api/v1/dashboard/outlets/{outletId}/timecards/{staffId}/close")
    ClosedShift close(@PathVariable Long outletId, @PathVariable Long staffId, @Valid @RequestBody CloseShift request,
                      @AuthenticationPrincipal Jwt jwt) {
        return corrections.close(DashboardUser.of(jwt), outletId, staffId, request, Actor.of(jwt));
    }
}
