package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.auth.PinLockService;
import com.counterline.pos.auth.PinLockService.PinLockResponse;
import com.counterline.pos.auth.PinPad;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** PIN pads locked by too many wrong PINs: head office sees every outlet's, a manager their own outlet's. */
@RestController
@RequestMapping("/api/v1/dashboard/pin-locks")
@SecurityRequirement(name = "bearer")
class PinLocksController {

    private final PinLockService pinLocks;

    PinLocksController(PinLockService pinLocks) {
        this.pinLocks = pinLocks;
    }

    @Operation(summary = "The outlets' PIN pads locked now")
    @GetMapping
    List<PinLockResponse> locks(@AuthenticationPrincipal Jwt jwt) {
        return pinLocks.locks(DashboardUser.of(jwt));
    }

    @Operation(summary = "Unlock an outlet's PIN pad (TILL, CLOCK or SETUP) at once")
    @PostMapping("/{outletId}/{pad}/unlock")
    ResponseEntity<Void> unlock(@PathVariable Long outletId, @PathVariable PinPad pad, @AuthenticationPrincipal Jwt jwt) {
        pinLocks.unlock(outletId, pad, DashboardUser.of(jwt), jwt.getClaimAsString("name"));
        return ResponseEntity.noContent().build();
    }
}
