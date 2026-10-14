package com.counterline.pos.timecards;

import com.counterline.pos.auth.AuthDtos.PinRequest;
import com.counterline.pos.auth.Terminal;
import com.counterline.pos.timecards.TimecardService.ClockResult;
import com.counterline.pos.timecards.TimecardService.ClockStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearer")
class TimecardController {

    private final TimecardService timecards;

    TimecardController(TimecardService timecards) {
        this.timecards = timecards;
    }

    /** Clock in or out, as the staff member chose after entering their PIN. */
    record ClockRequest(
            @NotNull @Pattern(regexp = "\\d{6}", message = "must be exactly 6 digits") String pin,
            @NotNull TimecardEvent.Type type) {

        @Override
        public String toString() {
            return "ClockRequest[pin=******, type=" + type + "]";
        }
    }

    @Operation(summary = "Whether the staff member with this PIN is clocked in (records nothing)",
            description = "Any staff member of the terminal's outlet. A wrong PIN is 422.")
    @PostMapping("/api/v1/timecards/status")
    ClockStatus status(@Valid @RequestBody PinRequest request, HttpServletRequest httpRequest,
                       @AuthenticationPrincipal Jwt jwt) {
        return timecards.status(request.pin(), Terminal.of(jwt).outletId(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Clock In or Clock Out with a staff PIN",
            description = "Clocking in when already in (or out when already out) is 422. A wrong PIN is 422.")
    @PostMapping("/api/v1/timecards/clock")
    ClockResult clock(@Valid @RequestBody ClockRequest request, HttpServletRequest httpRequest,
                      @AuthenticationPrincipal Jwt jwt) {
        return timecards.clock(request.pin(), request.type(), Terminal.of(jwt).outletId(), httpRequest.getRemoteAddr());
    }
}
