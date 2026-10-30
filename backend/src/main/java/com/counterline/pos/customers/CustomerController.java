package com.counterline.pos.customers;

import com.counterline.pos.auth.TokenService;
import com.counterline.pos.common.PageResponse;
import com.counterline.pos.customers.CustomerDtos.CustomerRequest;
import com.counterline.pos.customers.CustomerDtos.CustomerResponse;
import com.counterline.pos.customers.CustomerDtos.CustomerSummary;
import com.counterline.pos.customers.CustomerDtos.SearchField;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Members are shared by all outlets. A till (any staff) searches, adds and attaches members; editing,
 * deactivating and reactivating are a manager's or head office's. A customer display's token is not accepted here.
 */
@RestController
@RequestMapping("/api/v1/customers")
@SecurityRequirement(name = "bearer")
class CustomerController {

    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(summary = "Search customers by reference ID (exact), or name / phone / email (contains)",
            description = "Phone search accepts local format: \"0112345\" finds +60112345…. A till must give a meaningful "
                    + "query (3 characters, 4 digits for a phone; 400 otherwise) and finds active members only. Only the "
                    + "dashboard may list everyone, deactivated members included (flagged active: false).")
    @GetMapping
    PageResponse<CustomerSummary> search(@RequestParam(defaultValue = "NAME") SearchField field,
                                         @RequestParam(required = false) @Size(max = 60) String q,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                                         @AuthenticationPrincipal Jwt jwt) {
        return customerService.search(field, q, page, size, TokenService.isDashboardSession(jwt));
    }

    @Operation(summary = "A customer", description = "A deactivated member is found (404 otherwise) only from the dashboard.")
    @GetMapping("/{customerId}")
    CustomerResponse get(@PathVariable Long customerId, @AuthenticationPrincipal Jwt jwt) {
        return TokenService.isDashboardSession(jwt) ? customerService.getIncludingInactive(customerId) : customerService.get(customerId);
    }

    @Operation(summary = "Add a customer",
            description = "409 if the phone, email or reference ID is already used, by a deactivated member too (reactivate them instead).")
    @PostMapping
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @Operation(summary = "Edit a customer (manager or head office)",
            description = "Send the version you loaded; 409 if someone else saved the customer since.")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @PutMapping("/{customerId}")
    CustomerResponse update(@PathVariable Long customerId, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(customerId, request);
    }

    @Operation(summary = "Deactivate a customer (manager or head office)",
            description = "They keep their identity, points and orders; no sale can use them until reactivated.")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @PostMapping("/{customerId}/deactivate")
    CustomerResponse deactivate(@PathVariable Long customerId) {
        return customerService.deactivate(customerId);
    }

    @Operation(summary = "Reactivate a deactivated customer (manager or head office)")
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @PostMapping("/{customerId}/reactivate")
    CustomerResponse reactivate(@PathVariable Long customerId) {
        return customerService.reactivate(customerId);
    }
}
