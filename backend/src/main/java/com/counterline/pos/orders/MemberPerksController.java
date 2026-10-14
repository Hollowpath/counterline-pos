package com.counterline.pos.orders;

import com.counterline.pos.catalog.CatalogService;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.customers.CustomerService;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.OwedItemService;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * What the till shows as soon as a member is added to the sale: their points, whether it is their
 * birthday month (a cake on the cart), and a new member's free gift while it is still theirs to take.
 * In the orders package because whether a reward was had comes from orders (see
 * {@link CustomerAnalyticsController}).
 */
@RestController
@SecurityRequirement(name = "bearer")
class MemberPerksController {

    /**
     * {@code redemptionCost}: points per Member FOC item (null when no redemption runs).
     * {@code newMemberGifts}: free items the member may still take, with when the offer ends.
     */
    /** {@code owedItems}: free items the staff forgot last time, to give on this sale. */
    record MemberPerks(int points, Integer redemptionCost, boolean birthdayMonth, List<Gift> newMemberGifts,
                       List<Owed> owedItems) {
    }

    record Owed(String promotion, int quantity, List<String> items, String reason) {
    }

    record Gift(String promotion, List<String> items, Instant availableUntil) {
    }

    private final CustomerService customerService;
    private final LoyaltyService loyaltyService;
    private final PromotionRepository promotionRepository;
    private final PromotionRules promotionRules;
    private final CatalogService catalogService;
    private final OwedItemService owedItemService;
    private final Clock clock;

    MemberPerksController(CustomerService customerService, LoyaltyService loyaltyService,
                          PromotionRepository promotionRepository, PromotionRules promotionRules,
                          CatalogService catalogService, OwedItemService owedItemService, Clock clock) {
        this.customerService = customerService;
        this.loyaltyService = loyaltyService;
        this.promotionRepository = promotionRepository;
        this.promotionRules = promotionRules;
        this.catalogService = catalogService;
        this.owedItemService = owedItemService;
        this.clock = clock;
    }

    @Operation(summary = "A member's points, birthday month and new-member gift, for the cart")
    @GetMapping("/api/v1/customers/{customerId}/perks")
    @Transactional(readOnly = true)
    MemberPerks perks(@PathVariable Long customerId) {
        customerService.get(customerId); // 404 for unknown or deleted customers
        Customer member = customerService.getForSale(customerId);
        Instant now = clock.instant();
        Instant giftEnds = PromotionRules.registrationRewardEnds(member);
        List<Gift> gifts = promotionRepository.findByTypeAndActiveTrue(Promotion.Type.REGISTRATION_REWARD).stream()
                .filter(promotion -> promotionRules.registrationRewardAvailable(member, promotion, now))
                .map(promotion -> new Gift(promotion.getName(), catalogService.getItemNamesForPromotion(promotion.getId()),
                        giftEnds))
                .filter(gift -> !gift.items().isEmpty())
                .toList();
        var cost = promotionRules.redemptionCost();
        return new MemberPerks(loyaltyService.balance(customerId), cost.isPresent() ? cost.getAsInt() : null,
                promotionRules.isBirthdayMonth(member, now), gifts, owedItems(customerId));
    }

    private List<Owed> owedItems(Long customerId) {
        return owedItemService.open(customerId).stream()
                .map(owed -> new Owed(owed.promotion(), owed.remaining(),
                        catalogService.getItemNamesForPromotion(owed.promotionId()), owed.reason()))
                .toList();
    }
}
