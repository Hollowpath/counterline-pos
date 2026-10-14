package com.counterline.pos.display;

import com.counterline.pos.auth.DisplaySession;
import com.counterline.pos.display.DisplayDtos.ConnectRequest;
import com.counterline.pos.display.DisplayDtos.DisplayConnection;
import com.counterline.pos.display.DisplayDtos.DisplayState;
import com.counterline.pos.display.DisplayDtos.MemberLoginRequest;
import com.counterline.pos.display.DisplayDtos.MemberState;
import com.counterline.pos.display.DisplayDtos.SignUpRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The customer display's own API, called with the display's token (role DISPLAY), which can call
 * nothing else.
 */
@RestController
@RequestMapping("/api/v1/display")
class DisplaySessionController {

    private final DisplayService displays;

    DisplaySessionController(DisplayService displays) {
        this.displays = displays;
    }

    @Operation(summary = "Connect a customer display with the access code shown on the POS (no authentication)",
            description = "Returns the display's token. Wrong codes are throttled like PIN logins.")
    @PostMapping("/connect")
    DisplayConnection connect(@Valid @RequestBody ConnectRequest request, HttpServletRequest httpRequest) {
        return displays.connect(request.accessCode(), httpRequest.getRemoteAddr());
    }

    @Operation(summary = "What to show: the cart and the member's status",
            description = "Polled every second; 204 when nothing has changed since version `since`.",
            security = @SecurityRequirement(name = "bearer"))
    @GetMapping("/state")
    ResponseEntity<DisplayState> state(@RequestParam(defaultValue = "-1") long since, @AuthenticationPrincipal Jwt jwt) {
        return displays.state(DisplaySession.of(jwt), since)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Member Login by phone number, while a sale is open; the till adds them to it",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/member-login")
    MemberState memberLogin(@Valid @RequestBody MemberLoginRequest request, @AuthenticationPrincipal Jwt jwt) {
        return displays.memberLogin(DisplaySession.of(jwt), request.phone());
    }

    @Operation(summary = "Member Sign Up, while a sale is open; the till adds them to it",
            security = @SecurityRequirement(name = "bearer"))
    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    MemberState signUp(@Valid @RequestBody SignUpRequest request, @AuthenticationPrincipal Jwt jwt) {
        return displays.signUp(DisplaySession.of(jwt), request);
    }

    @Operation(summary = "Cancel a login or sign-up the till hasn't added yet",
            security = @SecurityRequirement(name = "bearer"))
    @DeleteMapping("/member")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancelMember(@AuthenticationPrincipal Jwt jwt) {
        displays.cancelMember(DisplaySession.of(jwt));
    }
}
