package com.counterline.pos.orders;

import com.counterline.pos.customers.Customer;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.OwedItemService;
import com.counterline.pos.loyalty.LoyaltyService.Settlement;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Moves a member's points when their sale is paid or refunded, and records it on the order; a paid
 * sale also uses up any free items the member was owed and got on it.
 */
@Component
class OrderLoyalty {

    private final LoyaltyService loyaltyService;
    private final OwedItemService owedItems;

    OrderLoyalty(LoyaltyService loyaltyService, OwedItemService owedItems) {
        this.loyaltyService = loyaltyService;
        this.owedItems = owedItems;
    }

    /** After the order is paid and saved (the points entries refer to it). No-op without a member. */
    void settle(SaleOrder order, Instant at) {
        Customer member = order.getCustomer();
        if (member == null) {
            return;
        }
        Settlement settlement = loyaltyService.settle(member.getId(), member.getFullName(), order.getId(),
                order.getAmountPaid(), order.getPointsToRedeem(), at);
        order.recordLoyalty(settlement.before(), settlement.earned(), settlement.redeemed());
        useOwedItems(member, order);
    }

    private void useOwedItems(Customer member, SaleOrder order) {
        Map<Long, Integer> owed = owedItems.owedByPromotion(member.getId());
        if (owed.isEmpty()) {
            return;
        }
        Map<Long, Integer> given = new LinkedHashMap<>();
        for (SaleOrderPromotion applied : order.getPromotions()) {
            given.merge(applied.getPromotion().getId(), applied.getLine().getQuantity(), Integer::sum);
        }
        given.forEach((promotionId, quantity) -> {
            int used = Math.min(quantity, owed.getOrDefault(promotionId, 0));
            if (used > 0) {
                owedItems.use(member.getId(), promotionId, used);
            }
        });
    }

    /** A full refund undoes what the sale did to the member's points. */
    void reverse(SaleOrder order, Instant at) {
        if (order.getCustomer() == null || order.getLoyaltyPointsEarned() == null) {
            return;
        }
        loyaltyService.reverse(order.getCustomer().getId(), order.getId(),
                order.getLoyaltyPointsEarned(), order.getLoyaltyPointsRedeemed(), at);
    }
}
