package com.counterline.pos.discounts;

import com.counterline.pos.discounts.Discount.Scope;
import com.counterline.pos.discounts.DiscountService.DiscountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@SecurityRequirement(name = "bearer")
class DiscountController {

    private final DiscountService discounts;

    DiscountController(DiscountService discounts) {
        this.discounts = discounts;
    }

    @Operation(summary = "Discounts offered for an item (scope=ITEM) or the whole order (scope=ORDER)")
    @GetMapping("/api/v1/discounts")
    List<DiscountResponse> offered(@RequestParam Scope scope) {
        return discounts.offered(scope);
    }
}
