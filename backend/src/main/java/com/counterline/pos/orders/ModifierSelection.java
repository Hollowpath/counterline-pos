package com.counterline.pos.orders;

import com.counterline.pos.catalog.ModifierGroup;
import com.counterline.pos.catalog.ModifierOption;
import com.counterline.pos.common.BusinessRuleException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates the options picked for one unit of an item against its groups: every option must
 * belong to the item and be active, and each group needs exactly its "CHOOSE N" picks.
 */
final class ModifierSelection {

    record Chosen(ModifierOption option, int quantity) {
    }

    private ModifierSelection() {
    }

    /** @param picks option id → how many times it was picked (per unit of the line) */
    static List<Chosen> validate(String itemName, List<ModifierGroup> groups, Map<Long, Integer> picks) {
        Map<Long, ModifierOption> offered = new HashMap<>();
        groups.forEach(group -> group.getOptions().stream()
                .filter(ModifierOption::isActive)
                .forEach(option -> offered.put(option.getId(), option)));
        for (Long optionId : picks.keySet()) {
            if (!offered.containsKey(optionId)) {
                throw new BusinessRuleException("Option " + optionId + " is not available for " + itemName);
            }
        }

        List<Chosen> chosen = new ArrayList<>();
        for (ModifierGroup group : groups) {
            int picked = 0;
            for (ModifierOption option : group.getOptions()) {
                Integer quantity = picks.get(option.getId());
                if (quantity != null) {
                    chosen.add(new Chosen(option, quantity));
                    picked += quantity;
                }
            }
            if (picked != group.getChooseExactly()) {
                throw new BusinessRuleException("Choose exactly " + group.getChooseExactly() + " for "
                        + group.getName() + " (" + picked + " chosen)");
            }
        }
        return chosen;
    }

    /** Extra price per unit of the line: each option's price times how often it was picked. */
    static BigDecimal unitPrice(List<Chosen> chosen) {
        return chosen.stream()
                .map(c -> c.option().getPrice().multiply(BigDecimal.valueOf(c.quantity())))
                .reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
    }
}
