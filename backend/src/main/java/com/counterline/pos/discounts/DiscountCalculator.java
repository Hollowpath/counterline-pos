package com.counterline.pos.discounts;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * How several discounts on one amount combine (up to nine may be chosen):
 * each is worked out on the undiscounted amount — so the result does not depend on the order
 * they were picked in — and together they never take off more than the amount itself.
 * When the cap bites, discounts earlier in display order keep their full value.
 */
public final class DiscountCalculator {

    private DiscountCalculator() {
    }

    /** @return the amount each discount takes off {@code base}, in the order given */
    public static List<BigDecimal> amounts(BigDecimal base, List<Discount> discounts) {
        List<BigDecimal> amounts = new ArrayList<>(discounts.size());
        BigDecimal remaining = base;
        for (Discount discount : discounts) {
            BigDecimal amount = discount.amountOf(base).min(remaining);
            amounts.add(amount);
            remaining = remaining.subtract(amount);
        }
        return amounts;
    }
}
