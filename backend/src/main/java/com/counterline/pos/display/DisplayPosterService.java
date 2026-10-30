package com.counterline.pos.display;

import com.counterline.pos.catalog.CatalogService;
import com.counterline.pos.display.DisplayDtos.DisplayPosters;
import com.counterline.pos.display.DisplayDtos.Poster;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which promotional posters the customer display may show: those whose promotion is active and runs
 * today, in the outlet's time zone. Only a closed set of keys leaves here; the wording is the
 * display's own, so no promotion, item, member or sale data reaches the screen.
 */
@Service
@Transactional(readOnly = true)
class DisplayPosterService {

    /** How long an answer may be shown before the display asks again (or the outlet's day ends first). */
    static final Duration ANSWER_LIFETIME = Duration.ofMinutes(5);

    private final PromotionRepository promotions;
    private final CatalogService catalog;
    private final OutletProperties outlet;

    DisplayPosterService(PromotionRepository promotions, CatalogService catalog, OutletProperties outlet) {
        this.promotions = promotions;
        this.catalog = catalog;
        this.outlet = outlet;
    }

    DisplayPosters at(Instant now) {
        ZoneId zone = outlet.timeZone();
        LocalDate today = LocalDate.ofInstant(now, zone);
        Set<Poster> shown = EnumSet.noneOf(Poster.class);
        for (Promotion promotion : promotions.findByActiveTrue()) {
            if (promotion.runsOn(today)) {
                posterOf(promotion).ifPresent(shown::add);
            }
        }
        // The seasonal-flavour promotion rewards seasonal items only: no such item on sale today, no poster.
        if (shown.contains(Poster.SEASONAL) && !catalog.hasSeasonalItemOn(today)) {
            shown.remove(Poster.SEASONAL);
        }
        Instant midnight = today.plusDays(1).atStartOfDay(zone).toInstant();
        Instant validUntil = now.plus(ANSWER_LIFETIME);
        return new DisplayPosters(List.copyOf(shown), midnight.isBefore(validUntil) ? midnight : validUntil);
    }

    /** The poster that advertises this promotion, if it has one; other promotion types have none. */
    private static Optional<Poster> posterOf(Promotion promotion) {
        return switch (promotion.getType()) {
            case BUY_X_GET_FREE -> Optional.of(promotion.isSeasonalItemsOnly() ? Poster.SEASONAL : Poster.BUY_THREE);
            case BIRTHDAY_REWARD -> Optional.of(Poster.BIRTHDAY);
            case POINTS_REDEMPTION -> Optional.of(Poster.MEMBER_POINTS);
            // What a spend promotion gives is not known here, so its two posters differ only by who may have it.
            case MIN_SPEND -> Optional.of(promotion.isMembersOnly() ? Poster.SPEND_MEMBERS : Poster.SPEND_ANYONE);
            case REGISTRATION_REWARD, REVIEW_REWARD -> Optional.empty();
        };
    }
}
