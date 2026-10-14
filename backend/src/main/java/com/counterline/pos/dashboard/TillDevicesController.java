package com.counterline.pos.dashboard;

import com.counterline.pos.auth.DashboardUser;
import com.counterline.pos.auth.TillDeviceService;
import com.counterline.pos.auth.TillDeviceService.DeviceResponse;
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

/** The iPads set up as tills: head office sees every outlet's, a manager their own outlet's. */
@RestController
@RequestMapping("/api/v1/dashboard/devices")
@SecurityRequirement(name = "bearer")
class TillDevicesController {

    private final TillDeviceService devices;

    TillDevicesController(TillDeviceService devices) {
        this.devices = devices;
    }

    @Operation(summary = "The iPads in use as tills")
    @GetMapping
    List<DeviceResponse> devices(@AuthenticationPrincipal Jwt jwt) {
        return devices.devices(DashboardUser.of(jwt));
    }

    @Operation(summary = "Reset an iPad: it stops logging staff in until a manager sets it up again")
    @PostMapping("/{deviceId}/reset")
    ResponseEntity<Void> reset(@PathVariable Long deviceId, @AuthenticationPrincipal Jwt jwt) {
        devices.reset(deviceId, DashboardUser.of(jwt), jwt.getClaimAsString("name"));
        return ResponseEntity.noContent().build();
    }
}
