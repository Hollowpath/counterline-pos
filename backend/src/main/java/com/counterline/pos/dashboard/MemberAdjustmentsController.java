package com.counterline.pos.dashboard;

import com.counterline.pos.catalog.CatalogService;
import com.counterline.pos.customers.CustomerDtos.CustomerResponse;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.LoyaltyService.PointsHistory;
import com.counterline.pos.loyalty.OwedItemService;
import com.counterline.pos.loyalty.OwedItemService.OwedItem;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;

/**
 * Dashboard, head office and managers: correct a member's points, and give back free items the
 * staff forgot. Every change records why and who.
 */
@RestController
@SecurityRequirement(name = "bearer")
class MemberAdjustmentsController {

    private final CustomerService customerService;
    private final LoyaltyService loyaltyService;
    private final OwedItemService owedItemService;
    private final PromotionRepository promotionRepository;
    private final CatalogService catalogService;
    private final Clock clock;

    MemberAdjustmentsController(CustomerService customerService, LoyaltyService loyaltyService,
                                OwedItemService owedItemService, PromotionRepository promotionRepository,
                                CatalogService catalogService, Clock clock) {
        this.customerService = customerService;
        this.loyaltyService = loyaltyService;
        this.owedItemService = owedItemService;
        this.promotionRepository = promotionRepository;
        this.catalogService = catalogService;
        this.clock = clock;
    }

    record PointsAdjustment(@NotNull @Min(-10_000) @Max(10_000) Integer points, @NotBlank @Size(max = 200) String reason) {
    }

    record OwedItemRequest(@NotNull Long promotionId, @Min(1) @Max(10) int quantity,
                           @NotBlank @Size(max = 200) String reason) {
    }

    /** A promotion whose free items can be owed, with the items it makes free. */
    record OwablePromotion(Long id, String name, List<String> items) {
    }

    @Operation(summary = "A member's points: the balance and the latest entries (sales and adjustments)")
    @GetMapping("/api/v1/dashboard/customers/{customerId}/points")
    PointsHistory points(@PathVariable Long customerId) {
        customerService.get(customerId); // 404 for unknown or deleted customers
        return loyaltyService.history(customerId);
    }

    @Operation(summary = "Add or take away points, with a reason")
    @PostMapping("/api/v1/dashboard/customers/{customerId}/points")
    PointsHistory adjustPoints(@PathVariable Long customerId, @Valid @RequestBody PointsAdjustment request,
                               @AuthenticationPrincipal Jwt jwt) {
        CustomerResponse member = customerService.get(customerId);
        loyaltyService.adjust(customerId, member.fullName(), request.points(), request.reason().strip(),
                jwt.getClaimAsString("name"), clock.instant());
        return loyaltyService.history(customerId);
    }

    @Operation(summary = "Free items the member is still owed")
    @GetMapping("/api/v1/dashboard/customers/{customerId}/owed-items")
    List<OwedItem> owedItems(@PathVariable Long customerId) {
        customerService.get(customerId);
        return owedItemService.open(customerId);
    }

    @Operation(summary = "Owe the member free items the staff forgot to give",
            description = "They are free on the member's next sale with those items, whatever its own rules allow.")
    @PostMapping("/api/v1/dashboard/customers/{customerId}/owed-items")
    @ResponseStatus(HttpStatus.CREATED)
    OwedItem oweItems(@PathVariable Long customerId, @Valid @RequestBody OwedItemRequest request,
                      @AuthenticationPrincipal Jwt jwt) {
        customerService.get(customerId);
        return owedItemService.give(customerId, request.promotionId(), request.quantity(), request.reason().strip(),
                jwt.getClaimAsString("name"), clock.instant());
    }

    @Operation(summary = "The promotions whose free items can be owed (not points: adjust those instead)")
    @GetMapping("/api/v1/dashboard/promotions")
    List<OwablePromotion> promotions() {
        return Arrays.stream(Promotion.Type.values())
                .filter(type -> type != Promotion.Type.POINTS_REDEMPTION)
                .flatMap(type -> promotionRepository.findByTypeAndActiveTrue(type).stream())
                .map(promotion -> new OwablePromotion(promotion.getId(), promotion.getName(),
                        catalogService.getItemNamesForPromotion(promotion.getId())))
                .filter(promotion -> !promotion.items().isEmpty())
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }
}
