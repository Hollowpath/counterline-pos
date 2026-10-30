package com.counterline.pos.dashboard;

import com.counterline.pos.catalog.CatalogService;
import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.customers.CustomerDtos.CustomerResponse;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.LoyaltyService.PointsHistory;
import com.counterline.pos.loyalty.OwedItemService;
import com.counterline.pos.loyalty.OwedItemService.OwedItem;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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

import tools.jackson.databind.JsonNode;

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

    /**
     * {@code points} is read as the JSON it is, not coerced: only a whole JSON number from -10,000 to 10,000
     * (not 0) is accepted. A 2.5, "3" or true is refused, never truncated or converted; see {@link #wholePoints}.
     */
    record PointsAdjustment(
            @Schema(type = "integer", minimum = "-10000", maximum = "10000", description = "Whole points to add (+) or take away (−), not 0")
            JsonNode points,
            @Schema(maxLength = 200) String reason) {
    }

    static final String POINTS_RULE = "Points must be a whole number from -10,000 to 10,000, and not 0";
    static final int MAX_POINTS = 10_000;

    /** The adjustment's points, only if the request holds a JSON integer in range and not zero. */
    static int wholePoints(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new BadRequestException(POINTS_RULE);
        }
        int points = node.intValue();
        if (points == 0 || points < -MAX_POINTS || points > MAX_POINTS) {
            throw new BadRequestException(POINTS_RULE);
        }
        return points;
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
    PointsHistory adjustPoints(@PathVariable Long customerId, @RequestBody PointsAdjustment request,
                               @AuthenticationPrincipal Jwt jwt) {
        int points = wholePoints(request.points());
        String reason = request.reason() == null ? "" : request.reason().strip();
        if (reason.isEmpty()) {
            throw new BadRequestException("Give a reason for the adjustment");
        }
        if (request.reason().length() > 200) {
            throw new BadRequestException("The reason can be at most 200 characters");
        }
        CustomerResponse member = customerService.get(customerId);
        loyaltyService.adjust(customerId, member.fullName(), points, reason, jwt.getClaimAsString("name"), clock.instant());
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
