package com.counterline.pos.catalog;

import com.counterline.pos.promotions.Promotion;

import java.math.BigDecimal;
import java.util.List;

/** Response bodies of the catalogue API. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record TabResponse(Long id, String name, List<FolderResponse> folders) {
    }

    public record FolderResponse(Long categoryId, String name, int row, int col, long itemCount) {
    }

    public record CategoryItemsResponse(Long categoryId, String name, List<ItemResponse> items) {
    }

    /**
     * {@code hasOptions}: tapping the item opens the item dialog instead of adding it directly.
     * {@code promotion}: set on promotional items, which are free when it applies.
     * {@code countsForPromotions}: bought, it counts towards "Buy 3" (the till lists these first).
     */
    public record ItemResponse(Long id, String name, BigDecimal price, String imageUrl, boolean hasOptions,
                               PromotionSummary promotion, boolean countsForPromotions) {
    }

    /**
     * {@code pointsCost}: points per item for a Member Redemption, otherwise 0. {@code membersOnly}:
     * the item needs a member on the sale (every promotion but the free recycle bags).
     */
    public record PromotionSummary(Long id, String name, Promotion.Type type, int pointsCost, boolean membersOnly) {

        public static PromotionSummary of(Promotion promotion) {
            return promotion == null ? null
                    : new PromotionSummary(promotion.getId(), promotion.getName(), promotion.getType(),
                            promotion.pointsPerItem(), promotion.isMembersOnly());
        }
    }

    public record ItemOptionsResponse(Long itemId, String name, BigDecimal price, List<ModifierGroupResponse> groups) {
    }

    public record ModifierGroupResponse(Long id, String name, int chooseExactly, List<ModifierOptionResponse> options) {
    }

    public record ModifierOptionResponse(Long id, String name, BigDecimal price) {
    }
}
