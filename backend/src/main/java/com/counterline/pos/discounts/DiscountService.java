package com.counterline.pos.discounts;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.discounts.Discount.Scope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class DiscountService {

    private final DiscountRepository discountRepository;

    DiscountService(DiscountRepository discountRepository) {
        this.discountRepository = discountRepository;
    }

    /**
     * {@code requiresApproval}: a manager must approve it at the till. {@code notForMembers}: it
     * can't be given on a member's sale (the staff discount). {@code countsAsVoucher}: its sale gives no free
     * items but the free recycle bags and the Member FOC items (the till's RM10 Voucher).
     */
    public record DiscountResponse(Long id, String name, Discount.Kind kind, BigDecimal value, boolean requiresApproval,
                                   boolean notForMembers, boolean countsAsVoucher) {

        public static DiscountResponse from(Discount discount) {
            return new DiscountResponse(discount.getId(), discount.getName(), discount.getKind(), discount.getValue(),
                    discount.isRequiresApproval(), discount.isNotForMembers(), discount.isVoucher());
        }
    }

    /** Discounts offered in the item dialog (ITEM) or on the % tab (ORDER), in display order. */
    public List<DiscountResponse> offered(Scope target) {
        return discountRepository.findByActiveTrueOrderBySortOrderAsc().stream()
                .filter(discount -> discount.getScope().allows(target))
                .map(DiscountResponse::from)
                .toList();
    }

    /**
     * Resolves requested discount ids for one line or order: each must be active, allowed there,
     * and requested once. Returned in display order, which is the order the cap is applied in.
     */
    public List<Discount> resolve(Collection<Long> ids, Scope target) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessRuleException("A discount can be applied only once");
        }
        Map<Long, Discount> found = discountRepository.findAllById(ids).stream()
                .filter(Discount::isActive)
                .collect(Collectors.toMap(Discount::getId, Function.identity()));
        for (Long id : ids) {
            Discount discount = found.get(id);
            if (discount == null || !discount.getScope().allows(target)) {
                throw new BusinessRuleException("Discount " + id + " cannot be applied to "
                        + (target == Scope.ITEM ? "an item" : "the order"));
            }
        }
        return found.values().stream()
                .sorted(Comparator.comparingInt(Discount::getSortOrder).thenComparing(Discount::getId))
                .toList();
    }
}
