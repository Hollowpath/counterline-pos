package com.counterline.pos.discounts;

import com.counterline.pos.discounts.Discount.Kind;
import com.counterline.pos.discounts.Discount.Scope;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountCalculatorTest {

    private static Discount percent(String value) {
        return new Discount(value + "%", Kind.PERCENT, new BigDecimal(value), Scope.BOTH);
    }

    private static Discount amount(String value) {
        return new Discount("RM" + value, Kind.AMOUNT, new BigDecimal(value), Scope.BOTH);
    }

    @Test
    void percentagesRoundHalfUpToTheSen() {
        // 16.50 × 15% = 2.475 → 2.48
        assertThat(DiscountCalculator.amounts(new BigDecimal("16.50"), List.of(percent("15"))))
                .containsExactly(new BigDecimal("2.48"));
    }

    @Test
    void eachDiscountIsWorkedOutOnTheUndiscountedAmount() {
        // 100.00: 30% → 30.00 and 10% → 10.00 (not 10% of 70.00), plus RM10.00
        assertThat(DiscountCalculator.amounts(new BigDecimal("100.00"), List.of(percent("30"), percent("10"), amount("10"))))
                .containsExactly(new BigDecimal("30.00"), new BigDecimal("10.00"), new BigDecimal("10.00"));
    }

    @Test
    void togetherTheyNeverExceedTheAmountAndEarlierDiscountsKeepTheirValue() {
        assertThat(DiscountCalculator.amounts(new BigDecimal("16.00"), List.of(percent("30"), percent("100"))))
                .containsExactly(new BigDecimal("4.80"), new BigDecimal("11.20"));
        assertThat(DiscountCalculator.amounts(new BigDecimal("8.00"), List.of(amount("10"))))
                .containsExactly(new BigDecimal("8.00"));
    }

    @Test
    void nothingToDiscount() {
        assertThat(DiscountCalculator.amounts(new BigDecimal("0.00"), List.of(percent("20"), amount("10"))))
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));
        assertThat(DiscountCalculator.amounts(new BigDecimal("12.00"), List.of())).isEmpty();
    }
}
