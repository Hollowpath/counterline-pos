package com.counterline.pos.orders;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.customers.Customer;
import com.counterline.pos.loyalty.LoyaltyService;
import com.counterline.pos.loyalty.LoyaltyService.Settlement;
import com.counterline.pos.loyalty.OwedItemService;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import org.springframework.stereotype.Component;

import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;

/**
 * Whether a sale may go ahead, as the outlet runs its promotions:
 * <ul>
 *   <li>promotional items need a member on the sale, except the free recycle bags;</li>
 *   <li>Buy 3 Free 1: one free item for every 3 qualifying items bought, no limit (a gift box
 *       counts as one). In the member's birth month the free item may be the birthday can instead
 *       (up to the promotion's per-receipt limit): both draw on the same "every 3 items";</li>
 *   <li>the seasonal-flavour promotion: one more free item for every 3 items bought with a seasonal
 *       flavour among them, so no more than the seasonal flavours bought (3 Unicorn: 1; 5 others
 *       and 1 Unicorn: 1; 6 Unicorn: 2; 2 Unicorn alone: none);</li>
 *   <li>Spend RMx: one free item per RMx of the sale total (one tote bag per RM150);</li>
 *   <li>those free items are not optional: a member's sale can't be paid until every one they
 *       have earned is in it (Buy 3 Free 1 or birthday, seasonal flavour, and the members' Spend RMx
 *       gift, the tote bag). The free recycle bags (open to everyone) and once-only rewards are
 *       optional;</li>
 *   <li>Member Redemption: the member's points, plus what this sale earns, must cover the cost;</li>
 *   <li>Registration Reward once per member, within 24 hours of signing up; Google Review reward
 *       once per member at each outlet (each outlet is reviewed on its own);</li>
 *   <li>limited-time promotions only between their dates; per-sale limits; one item per
 *       exclusive group (e.g. one free recycle bag);</li>
 *   <li>a discount barred from members' sales (the staff discount) can't be given with a member;</li>
 *   <li>a sale with a voucher (a dashboard voucher, or a discount that counts as one, like the till's
 *       RM10 Voucher) or any discount, on the order or on a line, gives none of the free items earned by the items bought (Buy 3 Free 1, the
 *       seasonal flavour's, the birthday can), nor are they required; the Member FOC items (points
 *       are still earned and redeemed at 7) and the FOC Other Items (bags, tote, Registration and
 *       Google Review rewards) work as usual.</li>
 * </ul>
 * Items a member is owed (given on the dashboard because the staff forgot them) are free first,
 * whatever the sale's rules allow, except on a voucher sale (they stay owed until a sale without one).
 *
 * <p>And for every member's sale: points are not carried forward once they reach the redemption cost,
 * so a sale that would leave the member with 7 or more must redeem them.
 *
 * <p>Promotional lines are already deducted in full by {@link SaleOrder}; this only decides whether
 * the sale may go ahead, listing every problem so the cashier sees them all at once.
 */
@Component
class PromotionRules {

    /** How long after signing up a new member may take the Registration Reward. */
    static final Duration REGISTRATION_REWARD_WINDOW = Duration.ofHours(24);

    static final String NO_FREE_ITEMS_WITH_VOUCHER = "No Buy 3 Free 1, seasonal flavour or birthday free items "
            + "with a voucher or discount: remove ";

    static final String NEEDS_MEMBER = "Promotional items need a member. Add the customer's membership to the sale, "
            + "or remove the promotional items.";

    private final SaleOrderRepository orderRepository;
    private final PromotionRepository promotionRepository;
    private final OwedItemService owedItems;
    private final OutletProperties outlet;

    PromotionRules(SaleOrderRepository orderRepository, PromotionRepository promotionRepository,
                   OwedItemService owedItems, OutletProperties outlet) {
        this.orderRepository = orderRepository;
        this.owedItems = owedItems;
        this.promotionRepository = promotionRepository;
        this.outlet = outlet;
    }

    /**
     * Promotions that share one "1 free per N items bought" allowance: every qualifying item, or
     * only N items with a seasonal flavour among them.
     */
    private record Allowance(boolean seasonalItemsOnly, int buyQuantity) {

        static Allowance of(Promotion promotion) {
            return new Allowance(promotion.isSeasonalItemsOnly(), promotion.getBuyQuantity());
        }
    }

    /**
     * @param pointsBalance the member's points before this sale (0 without a member)
     * @param editedOrderId the placed order being edited (its own once-only rewards don't count), or 0
     */
    List<String> problems(SaleOrder order, int pointsBalance, Instant now, long editedOrderId) {
        Customer member = order.getCustomer();
        List<String> problems = new ArrayList<>();
        if (member == null) {
            if (order.getPromotions().stream().anyMatch(applied -> applied.getPromotion().isMembersOnly())) {
                return List.of(NEEDS_MEMBER);
            }
            problems.addAll(promotionProblems(order, null, now, editedOrderId));
            return problems.stream().filter(Objects::nonNull).distinct().toList();
        }
        problems.addAll(discountProblems(order, member));
        problems.addAll(promotionProblems(order, member, now, editedOrderId));
        problems.addAll(pointsProblems(order, member, pointsBalance));
        return problems.stream().filter(Objects::nonNull).distinct().toList();
    }

    /** Throws with every problem when the sale does not qualify. */
    void requireSatisfied(SaleOrder order, int pointsBalance, Instant now, long editedOrderId) {
        throwIfAny(problems(order, pointsBalance, now, editedOrderId));
    }

    /**
     * The redemption cost (7 points): the cheapest active Member Redemption. Empty when none runs,
     * and then points simply accumulate.
     */
    OptionalInt redemptionCost() {
        return promotionRepository.findByTypeAndActiveTrue(Promotion.Type.POINTS_REDEMPTION).stream()
                .mapToInt(Promotion::pointsPerItem)
                .filter(points -> points > 0)
                .min();
    }

    /** Whether the member may still take this Registration Reward at {@code now}. */
    boolean registrationRewardAvailable(Customer member, Promotion promotion, Instant now) {
        return now.isBefore(registrationRewardEnds(member))
                && !orderRepository.memberHasHadPromotion(member.getId(), promotion.getId(), 0L);
    }

    static Instant registrationRewardEnds(Customer member) {
        return member.getCreatedAt().plus(REGISTRATION_REWARD_WINDOW);
    }

    boolean isBirthdayMonth(Customer member, Instant now) {
        return member.getDateOfBirth() != null
                && member.getDateOfBirth().getMonth() == LocalDate.ofInstant(now, outlet.timeZone()).getMonth();
    }

    private static void throwIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new BusinessRuleException(String.join(" ", problems));
        }
    }

    /** The staff discount is for staff purchases, so it can't be given on a member's sale. */
    private static List<String> discountProblems(SaleOrder order, Customer member) {
        return order.getDiscounts().stream()
                .filter(applied -> applied.getDiscount().isNotForMembers())
                .map(applied -> applied.getName() + " can't be given on a member's sale. Remove "
                        + member.getFullName() + " or the discount.")
                .toList();
    }

    /** {@code member} is null for a sale without one (then only promotions open to everyone are in it). */
    private List<String> promotionProblems(SaleOrder order, Customer member, Instant now, long editedOrderId) {
        LocalDate today = LocalDate.ofInstant(now, outlet.timeZone());
        List<String> problems = new ArrayList<>();

        Map<Promotion, Integer> given = new LinkedHashMap<>();
        for (SaleOrderPromotion applied : order.getPromotions()) {
            Promotion promotion = applied.getPromotion();
            given.merge(promotion, applied.getLine().getQuantity(), Integer::sum);
            if (!promotion.isActive()) {
                problems.add(applied.getLine().getItemName() + " is no longer on promotion.");
            }
        }
        // Items the member is owed (the staff forgot them last time) are free whatever this sale's
        // rules allow; only the rest is checked.
        if (member != null) {
            Map<Long, Integer> owed = owedItems.owedByPromotion(member.getId());
            given.replaceAll((promotion, quantity) -> quantity - Math.min(quantity, owed.getOrDefault(promotion.getId(), 0)));
        }
        given.values().removeIf(quantity -> quantity == 0);
        // A sale with any voucher or discount (on the order or on a line; the order's discounts include its
        // lines'): no free items earned by what is bought (Buy 3 Free 1, seasonal, birthday), and none of
        // those is owed.
        boolean voucherSale = !order.getDiscounts().isEmpty();
        if (voucherSale) {
            // Owed items too: they stay owed, for a sale without a voucher or discount.
            List<String> refused = order.getPromotions().stream()
                    .filter(applied -> !allowedWithVoucher(applied.getPromotion()))
                    .map(applied -> applied.getLine().getItemName())
                    .distinct()
                    .toList();
            if (!refused.isEmpty()) {
                problems.add(NO_FREE_ITEMS_WITH_VOUCHER + String.join(", ", refused) + ".");
            }
            given.keySet().removeIf(promotion -> !allowedWithVoucher(promotion));
        }
        Map<String, Integer> givenPerGroup = new LinkedHashMap<>();
        given.forEach((promotion, quantity) -> {
            if (promotion.getExclusiveGroup() != null) {
                givenPerGroup.merge(promotion.getExclusiveGroup(), quantity, Integer::sum);
            }
        });

        given.forEach((promotion, quantity) -> {
            if (!promotion.runsOn(today)) {
                problems.add(promotion.getName() + " runs from " + promotion.getStartsOn() + " to "
                        + promotion.getEndsOn() + ".");
            }
            if (promotion.getMaxPerSale() != null && quantity > promotion.getMaxPerSale()) {
                problems.add(promotion.getName() + ": at most " + promotion.getMaxPerSale() + " per receipt.");
            }
            switch (promotion.getType()) {
                case MIN_SPEND -> {
                    int allowed = order.getTotal().divide(promotion.getMinSpend(), 0, RoundingMode.DOWN).intValueExact();
                    if (allowed == 0) {
                        problems.add(promotion.getName() + " needs a sale of RM" + promotion.getMinSpend()
                                + " or more (this sale is RM" + order.getTotal() + ").");
                    } else if (quantity > allowed) {
                        problems.add(promotion.getName() + ": 1 free for every RM" + promotion.getMinSpend()
                                + " spent. This sale is RM" + order.getTotal() + ", so " + allowed
                                + " free, but " + quantity + " given.");
                    }
                }
                case REGISTRATION_REWARD -> {
                    if (member == null) {
                        problems.add(NEEDS_MEMBER);
                    } else if (orderRepository.memberHasHadPromotion(member.getId(), promotion.getId(), editedOrderId)) {
                        problems.add(member.getFullName() + " has already had the " + promotion.getName() + ".");
                    } else if (!now.isBefore(registrationRewardEnds(member))) {
                        problems.add("The " + promotion.getName() + " is for new members, within 24 hours of "
                                + "signing up. " + member.getFullName() + " signed up on "
                                + LocalDate.ofInstant(member.getCreatedAt(), outlet.timeZone()) + ".");
                    }
                }
                case REVIEW_REWARD -> {
                    if (member == null) {
                        problems.add(NEEDS_MEMBER);
                    } else if (orderRepository.memberHasHadPromotionAtOutlet(member.getId(), promotion.getId(),
                            order.getOutletId(), editedOrderId)) {
                        problems.add(member.getFullName() + " has already had the " + promotion.getName()
                                + " at this outlet.");
                    }
                }
                case BIRTHDAY_REWARD -> {
                    if (member == null) {
                        problems.add(NEEDS_MEMBER);
                    } else {
                        birthdayProblem(member, promotion, today).ifPresent(problems::add);
                    }
                }
                case BUY_X_GET_FREE, POINTS_REDEMPTION -> {
                    // Allowances and points are checked across promotions below.
                }
            }
        });

        // "1 free per N items bought": no more than earned and, for a member, no fewer either.
        // By id: a promotion reached through an item may be a different (proxy) instance.
        Map<Long, Integer> givenById = new LinkedHashMap<>();
        given.forEach((promotion, quantity) -> givenById.merge(promotion.getId(), quantity, Integer::sum));
        Map<Allowance, Map<Long, Promotion>> allowances = new LinkedHashMap<>();
        Stream.concat(given.keySet().stream().filter(promotion -> promotion.getBuyQuantity() != null),
                        member == null || voucherSale ? Stream.empty() : runningAllowancePromotions(today))
                .forEach(promotion -> allowances.computeIfAbsent(Allowance.of(promotion), key -> new LinkedHashMap<>())
                        .putIfAbsent(promotion.getId(), promotion));
        allowances.forEach((allowance, promotions) -> allowanceProblem(order, member, today, allowance,
                List.copyOf(promotions.values()), givenById).ifPresent(problems::add));

        if (member != null) {
            runningMemberSpendPromotions(today).forEach(promotion -> {
                int allowed = order.getTotal().divide(promotion.getMinSpend(), 0, RoundingMode.DOWN).intValueExact();
                int givenQuantity = givenById.getOrDefault(promotion.getId(), 0);
                if (givenQuantity < allowed) {
                    problems.add("Give the free gift: " + member.getFullName() + " spent RM" + order.getTotal()
                            + ", so gets " + allowed + " free with " + promotion.getName() + " (1 for every RM"
                            + promotion.getMinSpend() + " spent), " + givenQuantity + " given. Add "
                            + (allowed - givenQuantity) + " more from the Promotional tab.");
                }
            });
        }

        givenPerGroup.forEach((group, quantity) -> {
            if (quantity > 1) {
                problems.add("Only one " + group + " per sale.");
            }
        });
        return problems;
    }

    /**
     * What a voucher sale may still give: the Member FOC items (bought with points) and the FOC Other
     * Items (the Spend RMx bags and tote, the Registration and Google Review rewards), as usual. Only
     * the free items earned by the items bought are not given: Buy 3 Free 1, the seasonal flavour's
     * and the birthday can.
     */
    private static boolean allowedWithVoucher(Promotion promotion) {
        return switch (promotion.getType()) {
            case POINTS_REDEMPTION, MIN_SPEND, REGISTRATION_REWARD, REVIEW_REWARD -> true;
            case BUY_X_GET_FREE, BIRTHDAY_REWARD -> false;
        };
    }

    /**
     * The members' Spend RMx gifts running today (the tote bag), which a member must be given. The
     * free recycle bags are open to everyone, and optional.
     */
    private Stream<Promotion> runningMemberSpendPromotions(LocalDate today) {
        return promotionRepository.findByTypeAndActiveTrue(Promotion.Type.MIN_SPEND).stream()
                .filter(promotion -> promotion.isMembersOnly() && promotion.runsOn(today));
    }

    /** The "1 free per N items bought" promotions running today, which a member must be given. */
    private Stream<Promotion> runningAllowancePromotions(LocalDate today) {
        return Stream.of(Promotion.Type.BUY_X_GET_FREE, Promotion.Type.BIRTHDAY_REWARD)
                .flatMap(type -> promotionRepository.findByTypeAndActiveTrue(type).stream())
                .filter(promotion -> promotion.getBuyQuantity() != null && promotion.runsOn(today));
    }

    /**
     * Points must cover what the sale redeems, and are not carried forward: once the member would
     * have the redemption cost or more after the sale, they redeem them on the spot (6 points and
     * an RM80 sale make 14: 2 Member FOC items).
     */
    private List<String> pointsProblems(SaleOrder order, Customer member, int pointsBalance) {
        Settlement settlement = new Settlement(pointsBalance, LoyaltyService.pointsEarned(order.getTotal()),
                order.getPointsToRedeem());
        if (settlement.redeemed() > 0) {
            try {
                LoyaltyService.requireEnough(member.getFullName(), settlement);
            } catch (BusinessRuleException e) {
                return List.of(e.getMessage() + ".");
            }
        }
        OptionalInt cost = redemptionCost();
        if (cost.isEmpty() || settlement.after() < cost.getAsInt()) {
            return List.of();
        }
        int toRedeem = settlement.after() / cost.getAsInt();
        return List.of(member.getFullName() + " will have " + settlement.after() + " points after this sale. "
                + "Points can't be carried forward once they reach " + cost.getAsInt() + ": redeem " + toRedeem
                + (settlement.redeemed() > 0 ? " more" : "") + " Member FOC item" + (toRedeem == 1 ? "" : "s")
                + " from the Promotional tab.");
    }

    /**
     * "1 free item for every N items bought", shared by the promotions drawing on it (Buy 3 Free 1
     * and the birthday upsize, or the seasonal flavour's). Items bought: lines that are not
     * promotional, from categories that count towards promotions. The seasonal flavour's needs a
     * seasonal flavour among each N, so it is never more than the seasonal flavours bought.
     * More free items than earned is never allowed; fewer only without a member, who can't have them.
     */
    private Optional<String> allowanceProblem(SaleOrder order, Customer member, LocalDate today, Allowance allowance,
                                              List<Promotion> promotions, Map<Long, Integer> givenById) {
        List<SaleOrderLine> bought = order.getLines().stream()
                .filter(line -> line.getItem() != null && line.getItem().getPromotion() == null)
                .filter(line -> line.getItem().getCategory().isCountsForPromotions())
                .toList();
        int boughtCount = bought.stream().mapToInt(SaleOrderLine::getQuantity).sum();
        int allowed = boughtCount / allowance.buyQuantity();
        String per = allowance.buyQuantity() == 1 ? "item bought" : allowance.buyQuantity() + " items bought";
        String boughtText = boughtCount + " bought";
        if (allowance.seasonalItemsOnly()) {
            int seasonal = bought.stream()
                    .filter(line -> line.getItem().isSeasonalOn(today))
                    .mapToInt(SaleOrderLine::getQuantity)
                    .sum();
            allowed = Math.min(allowed, seasonal);
            per += " with a seasonal flavour among them";
            boughtText += ", " + seasonal + " of them seasonal";
        }
        int givenTotal = promotions.stream().mapToInt(promotion -> givenById.getOrDefault(promotion.getId(), 0)).sum();
        if (givenTotal > allowed) {
            List<Promotion> givenOnes = promotions.stream()
                    .filter(promotion -> givenById.getOrDefault(promotion.getId(), 0) > 0)
                    .toList();
            return Optional.of(names(givenOnes, " and ") + ": 1 free item for every " + per + ". This sale has "
                    + boughtText + ", so " + allowed + " free, but " + givenTotal + " given.");
        }
        if (member != null && givenTotal < allowed) {
            // The birthday can is only an option in the member's birth month.
            List<Promotion> choices = promotions.stream()
                    .filter(promotion -> promotion.getType() != Promotion.Type.BIRTHDAY_REWARD
                            || birthdayProblem(member, promotion, today).isEmpty())
                    .toList();
            int missing = allowed - givenTotal;
            return Optional.of("Give the free gift: " + member.getFullName() + " gets " + allowed + " free with "
                    + names(choices.isEmpty() ? promotions : choices, " or ") + " (1 for every "
                    + per + "), " + givenTotal + " given. Add " + missing + " more from the Promotional tab.");
        }
        return Optional.empty();
    }

    private static String names(List<Promotion> promotions, String separator) {
        return String.join(separator, promotions.stream().map(Promotion::getName).toList());
    }

    /** In the member's birth month only. */
    private Optional<String> birthdayProblem(Customer member, Promotion promotion, LocalDate today) {
        LocalDate birthday = member.getDateOfBirth();
        if (birthday == null) {
            return Optional.of(member.getFullName() + " has no date of birth on file for the "
                    + promotion.getName() + ".");
        }
        if (birthday.getMonth() != today.getMonth()) {
            return Optional.of("The " + promotion.getName() + " is given in " + member.getFullName()
                    + "'s birthday month (" + monthName(birthday.getMonth()) + ").");
        }
        return Optional.empty();
    }

    private static String monthName(Month month) {
        return month.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }
}
