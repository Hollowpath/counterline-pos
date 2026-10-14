package com.counterline.pos.discounts;

import com.counterline.pos.discounts.DiscountAdminService.AdminDiscount;
import com.counterline.pos.discounts.DiscountAdminService.DiscountRequest;
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

/** Dashboard, head office only: the discounts offered at every till. */
@RestController
@RequestMapping("/api/v1/dashboard/discounts")
@SecurityRequirement(name = "bearer")
class DiscountAdminController {

    private final DiscountAdminService discountAdmin;

    DiscountAdminController(DiscountAdminService discountAdmin) {
        this.discountAdmin = discountAdmin;
    }

    @Operation(summary = "Every discount, offered or not")
    @GetMapping
    List<AdminDiscount> discounts() {
        return discountAdmin.discounts();
    }

    @Operation(summary = "Add a discount")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminDiscount create(@Valid @RequestBody DiscountRequest request) {
        return discountAdmin.create(request);
    }

    @Operation(summary = "Change a discount, or stop offering it")
    @PutMapping("/{discountId}")
    AdminDiscount update(@PathVariable Long discountId, @Valid @RequestBody DiscountRequest request) {
        return discountAdmin.update(discountId, request);
    }
}
