package com.counterline.pos.customers;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/customers")
@SecurityRequirement(name = "bearer")
class CustomerController {

    private final CustomerService customerService;

    CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @Operation(summary = "Search customers by reference ID (exact), or name / phone / email (contains)",
            description = "Phone search accepts local format: \"0112345\" finds +60112345…")
    @GetMapping
    PageResponse<CustomerSummary> search(@RequestParam(defaultValue = "NAME") SearchField field,
                                         @RequestParam(required = false) @Size(max = 60) String q,
                                         @RequestParam(defaultValue = "0") @Min(0) int page,
                                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return customerService.search(field, q, page, size);
    }

    @GetMapping("/{customerId}")
    CustomerResponse get(@PathVariable Long customerId) {
        return customerService.get(customerId);
    }

    @Operation(summary = "Add a customer", description = "409 if the phone, email or reference ID is already used.")
    @PostMapping
    ResponseEntity<CustomerResponse> create(@Valid @RequestBody CustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/customers/" + created.id())).body(created);
    }

    @Operation(summary = "Edit a customer",
            description = "Send the version you loaded; 409 if someone else saved the customer since.")
    @PutMapping("/{customerId}")
    CustomerResponse update(@PathVariable Long customerId, @Valid @RequestBody CustomerRequest request) {
        return customerService.update(customerId, request);
    }

    @Operation(summary = "Delete a customer (past orders keep showing who they were sold to)")
    @DeleteMapping("/{customerId}")
    ResponseEntity<Void> delete(@PathVariable Long customerId) {
        customerService.delete(customerId);
        return ResponseEntity.noContent().build();
    }
}
