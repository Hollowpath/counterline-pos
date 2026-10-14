package com.counterline.pos.promotions;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Why a promotional item is free, and when it may be given. Promotional items are valued at their
 * normal price and deducted in full when the sale qualifies; every one needs a member on the sale.
 */
@Entity
@Table(name = "promotion")
public class Promotion {

    public enum Type {
        /**
         * One free item per {@code buyQuantity} qualifying items bought ("Buy 3 Free 1"), or, with
         * {@code seasonalItemsOnly}, per seasonal-flavour item bought.
         */
        BUY_X_GET_FREE,
        /** One free item per {@code minSpend} of the sale total ("Spend RM50", a tote bag per RM150). */
        MIN_SPEND,
        /** Costs the member {@code pointsCost} points per item ("Member Redemption"). */
        POINTS_REDEMPTION,
        /** Once per member (the Member Registration reward). */
        REGISTRATION_REWARD,
        /** Once per member at each outlet, for a Google review of that outlet shown to the staff. */
        REVIEW_REWARD,
        /**
         * The Buy 3 upsize in the member's birth month: shares the "every {@code buyQuantity}
         * items" allowance with Buy 3 Free 1.
         */
        BIRTHDAY_REWARD
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Type type;

    private Short buyQuantity;

    private BigDecimal minSpend;

    private Integer pointsCost;

    private Short maxPerSale;

    private String exclusiveGroup;

    /** Counts only the items marked as seasonal flavours (BUY_X_GET_FREE). */
    private boolean seasonalItemsOnly;

    /** Needs a member on the sale; false for the free recycle bags. */
    private boolean membersOnly = true;

    /** Limited-time promotions run on these dates (inclusive); null = open-ended. */
    private LocalDate startsOn;

    private LocalDate endsOn;

    private boolean active;

    protected Promotion() {
    }

    public Promotion(String name, Type type, Short buyQuantity, BigDecimal minSpend, Integer pointsCost,
                     Short maxPerSale, String exclusiveGroup) {
        this.name = name;
        this.type = type;
        this.buyQuantity = buyQuantity;
        this.minSpend = minSpend;
        this.pointsCost = pointsCost;
        this.maxPerSale = maxPerSale;
        this.exclusiveGroup = exclusiveGroup;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Type getType() {
        return type;
    }

    public Short getBuyQuantity() {
        return buyQuantity;
    }

    public BigDecimal getMinSpend() {
        return minSpend;
    }

    /** Points per item, or 0 when the promotion is not a points redemption. */
    public int pointsPerItem() {
        return pointsCost == null ? 0 : pointsCost;
    }

    public Short getMaxPerSale() {
        return maxPerSale;
    }

    public String getExclusiveGroup() {
        return exclusiveGroup;
    }

    public boolean isSeasonalItemsOnly() {
        return seasonalItemsOnly;
    }

    public boolean isMembersOnly() {
        return membersOnly;
    }

    /** Whether the promotion runs on {@code day}. */
    public boolean runsOn(LocalDate day) {
        return (startsOn == null || !day.isBefore(startsOn)) && (endsOn == null || !day.isAfter(endsOn));
    }

    public LocalDate getStartsOn() {
        return startsOn;
    }

    public LocalDate getEndsOn() {
        return endsOn;
    }

    public boolean isActive() {
        return active;
    }
}
