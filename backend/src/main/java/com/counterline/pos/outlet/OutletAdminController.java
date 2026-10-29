package com.counterline.pos.outlet;

import com.counterline.pos.outlet.OutletAdminService.AdminOutlet;
import com.counterline.pos.outlet.OutletAdminService.OutletEdit;
import com.counterline.pos.outlet.OutletAdminService.OutletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dashboard, head office only: the chain's outlets, open or closed. */
@RestController
@RequestMapping("/api/v1/dashboard/admin/outlets")
@SecurityRequirement(name = "bearer")
class OutletAdminController {

    private final OutletAdminService outlets;

    OutletAdminController(OutletAdminService outlets) {
        this.outlets = outlets;
    }

    @Operation(summary = "Every outlet, including deactivated ones")
    @GetMapping
    List<AdminOutlet> list() {
        return outlets.list();
    }

    @Operation(summary = "Add an outlet", description = "The code is unique and cannot be changed afterwards.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminOutlet create(@Valid @RequestBody OutletRequest request) {
        return outlets.create(request);
    }

    @Operation(summary = "Change an outlet's name and address (not its code)")
    @PutMapping("/{id}")
    AdminOutlet update(@PathVariable Long id, @Valid @RequestBody OutletEdit request) {
        return outlets.update(id, request);
    }

    @Operation(summary = "Deactivate an outlet",
            description = "Refused (409) while it has an open register. Past sales are kept; outlets are never deleted.")
    @PostMapping("/{id}/deactivate")
    AdminOutlet deactivate(@PathVariable Long id) {
        return outlets.deactivate(id);
    }

    @Operation(summary = "Reactivate an outlet")
    @PostMapping("/{id}/reactivate")
    AdminOutlet reactivate(@PathVariable Long id) {
        return outlets.reactivate(id);
    }
}
