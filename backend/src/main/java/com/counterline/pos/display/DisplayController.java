package com.counterline.pos.display;

import com.counterline.pos.auth.Terminal;
import com.counterline.pos.display.DisplayDtos.CartView;
import com.counterline.pos.display.DisplayDtos.ConfirmRequest;
import com.counterline.pos.display.DisplayDtos.DisplayStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The POS's side of its customer display: pairing, publishing the cart, confirming members. */
@RestController
@RequestMapping("/api/v1/displays")
@SecurityRequirement(name = "bearer")
class DisplayController {

    private final DisplayService displays;

    DisplayController(DisplayService displays) {
        this.displays = displays;
    }

    @Operation(summary = "Set up a customer display for this terminal",
            description = "Returns an access code; enter it on the display to connect.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DisplayStatus create(@AuthenticationPrincipal Jwt jwt) {
        return displays.create(Terminal.of(jwt));
    }

    @Operation(summary = "Is the display connected? With any member waiting to be added to the sale")
    @GetMapping("/{displayId}")
    DisplayStatus status(@PathVariable Long displayId, @AuthenticationPrincipal Jwt jwt) {
        return displays.status(displayId, Terminal.of(jwt).outletId());
    }

    @Operation(summary = "Show the cart on the display")
    @PutMapping("/{displayId}/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void publishCart(@PathVariable Long displayId, @Valid @RequestBody CartView cart, @AuthenticationPrincipal Jwt jwt) {
        displays.publishCart(displayId, Terminal.of(jwt).outletId(), cart);
    }

    @Operation(summary = "The member waiting on the display has been added to the sale")
    @PostMapping("/{displayId}/member/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmMember(@PathVariable Long displayId, @Valid @RequestBody ConfirmRequest request,
                       @AuthenticationPrincipal Jwt jwt) {
        displays.confirmMember(displayId, Terminal.of(jwt).outletId(), request.customerId());
    }

    @Operation(summary = "Disconnect the display")
    @DeleteMapping("/{displayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@PathVariable Long displayId, @AuthenticationPrincipal Jwt jwt) {
        displays.revoke(displayId, Terminal.of(jwt).outletId());
    }
}
