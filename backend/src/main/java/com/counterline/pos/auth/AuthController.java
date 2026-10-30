package com.counterline.pos.auth;

import com.counterline.pos.auth.AuthDtos.LoginRequest;
import com.counterline.pos.auth.AuthDtos.LoginResponse;
import com.counterline.pos.auth.AuthDtos.PinLockRequest;
import com.counterline.pos.auth.AuthDtos.PinLockStatus;
import com.counterline.pos.auth.AuthDtos.SpecialAccessRequest;
import com.counterline.pos.auth.AuthDtos.StaffResponse;
import com.counterline.pos.auth.AuthDtos.TerminalSetupRequest;
import com.counterline.pos.auth.AuthDtos.TerminalSetupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    private final AuthService authService;
    private final PinLockService pinLocks;

    AuthController(AuthService authService, PinLockService pinLocks) {
        this.authService = authService;
        this.pinLocks = pinLocks;
    }

    @Operation(summary = "Log in with a 6-digit staff PIN on an iPad set up as a till",
            description = "Returns a bearer token for the iPad's outlet. 410 when the iPad has been reset from the "
                    + "dashboard (it must be set up again). Repeated failures from one client are throttled (HTTP 429).")
    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        // Behind the ALB this is the real client address (server.forward-headers-strategy).
        return authService.login(request.pin(), request.deviceKey(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Set an iPad up as a till at an outlet, with that outlet's manager's PIN",
            description = "Registers the iPad and returns its key, which it keeps and logs in with. Only a reset "
                    + "from the dashboard undoes it. A wrong or non-manager PIN is 403. Shares the login throttle.")
    @PostMapping("/terminal-setup")
    TerminalSetupResponse setUpTerminal(@Valid @RequestBody TerminalSetupRequest request, HttpServletRequest httpRequest) {
        return authService.setUpTerminal(request.pin(), request.outletId(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "Manager approval at a logged-in terminal (\"Special Access Required\")",
            description = "Returns a short-lived manager token for one operation (DISCOUNT, VOID or REFUND, "
                    + "required), good once and only for that operation. A wrong or "
                    + "non-manager PIN is 403 (the terminal's session stays valid).",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/special-access")
    LoginResponse specialAccess(@Valid @RequestBody SpecialAccessRequest request, HttpServletRequest httpRequest,
                                @AuthenticationPrincipal Jwt jwt) {
        return authService.specialAccess(request.pin(), request.operation(), httpRequest.getRemoteAddr(), Terminal.of(jwt));
    }

    @Operation(summary = "Is the login (or set-up) PIN pad locked?",
            description = "For the PIN screens before anyone logs in: the till's PIN login by its iPad's key, or "
                    + "the outlet's iPad set-up by outletId. While locked, the screen blocks the keypad and asks "
                    + "again every few seconds, so a manager's unlock on the dashboard takes effect at once.")
    @PostMapping("/pin-lock")
    PinLockStatus pinLock(@Valid @RequestBody PinLockRequest request) {
        return new PinLockStatus(pinLocks.lockedForSeconds(request.deviceKey(), request.outletId()));
    }

    @Operation(summary = "Is this till's PIN pad (TILL: manager approvals, or CLOCK) locked?",
            security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/pin-lock/{pad}")
    PinLockStatus terminalPinLock(@PathVariable PinPad pad, @AuthenticationPrincipal Jwt jwt) {
        return new PinLockStatus(pinLocks.lockedForSeconds(Terminal.of(jwt), pad));
    }

    @Operation(summary = "The logged-in staff member", security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/me")
    StaffResponse me(@AuthenticationPrincipal Jwt jwt) {
        return authService.currentStaff(Long.valueOf(jwt.getSubject()));
    }
}
