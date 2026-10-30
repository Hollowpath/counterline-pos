package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.display.DisplayService;
import com.counterline.pos.display.DisplayService.DisplayListing;
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

/** The customer displays: head office sees every outlet's, a manager their own outlet's. */
@RestController
@RequestMapping("/api/v1/dashboard/displays")
@SecurityRequirement(name = "bearer")
class DashboardDisplaysController {

    private final DisplayService displays;

    DashboardDisplaysController(DisplayService displays) {
        this.displays = displays;
    }

    @Operation(summary = "The customer displays that are connected or waiting for their access code")
    @GetMapping
    List<DisplayListing> displays(@AuthenticationPrincipal Jwt jwt) {
        return displays.listFor(DashboardUser.of(jwt));
    }

    @Operation(summary = "Disconnect a customer display (for example a lost one): its token stops working at once")
    @PostMapping("/{displayId}/disconnect")
    ResponseEntity<Void> disconnect(@PathVariable Long displayId, @AuthenticationPrincipal Jwt jwt) {
        displays.disconnectFor(displayId, DashboardUser.of(jwt));
        return ResponseEntity.noContent().build();
    }
}
