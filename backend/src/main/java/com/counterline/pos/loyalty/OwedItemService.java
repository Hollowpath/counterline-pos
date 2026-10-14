package com.counterline.pos.loyalty;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.promotions.Promotion;
import com.counterline.pos.promotions.PromotionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Free items a member is owed because the staff forgot to give them. Given on the dashboard;
 * used up when the member's next sale with those items is paid.
 */
@Service
@Transactional
public class OwedItemService {

    private static final Logger log = LoggerFactory.getLogger(OwedItemService.class);
    private static final int MAX_AT_ONCE = 10;

    private final MemberOwedItemRepository owedRepository;
    private final PromotionRepository promotionRepository;

    OwedItemService(MemberOwedItemRepository owedRepository, PromotionRepository promotionRepository) {
        this.owedRepository = owedRepository;
        this.promotionRepository = promotionRepository;
    }

    /** One record of items owed. */
    public record OwedItem(Long id, Long promotionId, String promotion, int remaining, String reason, String givenBy,
                           Instant givenAt) {
    }

    /**
     * Owe the member {@code quantity} free items of a promotion. Not for Member Redemption: points
     * are adjusted instead.
     */
    public OwedItem give(Long customerId, Long promotionId, int quantity, String reason, String givenBy, Instant at) {
        if (quantity < 1 || quantity > MAX_AT_ONCE) {
            throw new BadRequestException("Owe between 1 and " + MAX_AT_ONCE + " items at a time");
        }
        Promotion promotion = promotionRepository.findById(promotionId)
                .filter(Promotion::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion " + promotionId + " not found"));
        if (promotion.getType() == Promotion.Type.POINTS_REDEMPTION) {
            throw new BadRequestException("For points, adjust the member's points instead");
        }
        MemberOwedItem owed = owedRepository.save(new MemberOwedItem(customerId, promotion, (short) quantity, reason, givenBy, at));
        log.info("Customer {} owed {} × {} by {}", customerId, quantity, promotion.getName(), givenBy);
        return toView(owed);
    }

    @Transactional(readOnly = true)
    public List<OwedItem> open(Long customerId) {
        return owedRepository.findOpen(customerId).stream().map(OwedItemService::toView).toList();
    }

    /** promotion id → how many of its items the member is still owed. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> owedByPromotion(Long customerId) {
        Map<Long, Integer> owed = new LinkedHashMap<>();
        for (MemberOwedItem item : owedRepository.findOpen(customerId)) {
            owed.merge(item.getPromotion().getId(), item.remaining(), Integer::sum);
        }
        return owed;
    }

    /** A paid sale gave the member {@code quantity} owed items of the promotion: use them up, oldest first. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void use(Long customerId, Long promotionId, int quantity) {
        int left = quantity;
        for (MemberOwedItem item : owedRepository.lockOpen(customerId, promotionId)) {
            if (left == 0) {
                break;
            }
            left -= item.use(left);
        }
    }

    private static OwedItem toView(MemberOwedItem item) {
        return new OwedItem(item.getId(), item.getPromotion().getId(), item.getPromotion().getName(), item.remaining(),
                item.getReason(), item.getGivenBy(), item.getCreatedAt());
    }
}
