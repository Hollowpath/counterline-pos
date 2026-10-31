package com.counterline.pos.staff;

import com.counterline.pos.auth.Actor;
import com.counterline.pos.staff.StaffAdminService.AdminStaff;
import com.counterline.pos.staff.StaffAdminService.PinReset;
import com.counterline.pos.staff.StaffAdminService.StaffEdit;
import com.counterline.pos.staff.StaffAdminService.StaffRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dashboard, head office only: the chain's staff. Responses never carry a PIN, password or hash. */
@RestController
@RequestMapping("/api/v1/dashboard/admin/staff")
@SecurityRequirement(name = "bearer")
class StaffAdminController {

    private final StaffAdminService staff;

    StaffAdminController(StaffAdminService staff) {
        this.staff = staff;
    }

    @Operation(summary = "Staff of every outlet, or of one outlet, including deactivated staff")
    @GetMapping
    List<AdminStaff> list(@RequestParam(required = false) Long outletId) {
        return staff.list(outletId);
    }

    @Operation(summary = "Add a cashier (STAFF) or a manager",
            description = "A six-digit PIN is required and must be unique, deactivated staff included. Only managers can be given a dashboard e-mail and password.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminStaff create(@Valid @RequestBody StaffRequest request, @AuthenticationPrincipal Jwt jwt) {
        return staff.create(Actor.of(jwt), request);
    }

    @Operation(summary = "Change name, role and outlet, and a manager's dashboard login",
            description = "Blank e-mail or password fields are left unchanged. Adding a login needs both.")
    @PutMapping("/{id}")
    AdminStaff update(@PathVariable Long id, @Valid @RequestBody StaffEdit request, @AuthenticationPrincipal Jwt jwt) {
        return staff.update(Actor.of(jwt), id, request);
    }

    @Operation(summary = "Remove a manager's dashboard login (e-mail and password); they keep their till PIN")
    @DeleteMapping("/{id}/dashboard-login")
    AdminStaff removeDashboardLogin(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return staff.removeDashboardLogin(Actor.of(jwt), id);
    }

    @Operation(summary = "Deactivate a staff member: they can no longer sign in. Their past sales and timecards are kept.")
    @PostMapping("/{id}/deactivate")
    AdminStaff deactivate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return staff.deactivate(Actor.of(jwt), id);
    }

    @Operation(summary = "Reactivate a staff member (their outlet must be open)")
    @PostMapping("/{id}/reactivate")
    AdminStaff reactivate(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return staff.reactivate(Actor.of(jwt), id);
    }

    @Operation(summary = "Set a new PIN; the PIN is never returned")
    @PostMapping("/{id}/pin")
    AdminStaff resetPin(@PathVariable Long id, @Valid @RequestBody PinReset request, @AuthenticationPrincipal Jwt jwt) {
        return staff.resetPin(Actor.of(jwt), id, request);
    }
}
