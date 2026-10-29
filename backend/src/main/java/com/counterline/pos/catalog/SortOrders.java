package com.counterline.pos.catalog;

import com.counterline.pos.common.BadRequestException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reorders rows that share a unique sort order (unique per parent, not deferrable): every row first
 * moves to a free negative position, then to its final one, so no step collides.
 */
final class SortOrders {

    private SortOrders() {
    }

    /**
     * @param current  every row of the parent, in any order
     * @param ids      the same rows' ids in the order wanted; anything else is a bad request
     * @param flush    writes pending changes (called between the two steps)
     */
    static <T> void apply(List<T> current, List<Long> ids, Function<T, Long> idOf, BiConsumer<T, Integer> setOrder,
                          Runnable flush, String what) {
        if (ids == null) {
            throw new BadRequestException("List the " + what + " in the order wanted");
        }
        Set<Long> wanted = new HashSet<>(ids);
        Set<Long> existing = current.stream().map(idOf).collect(Collectors.toSet());
        if (wanted.size() != ids.size() || !wanted.equals(existing)) {
            throw new BadRequestException("The order must list every one of the " + existing.size() + " " + what
                    + " exactly once");
        }
        Map<Long, T> byId = current.stream().collect(Collectors.toMap(idOf, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            setOrder.accept(byId.get(ids.get(i)), -(i + 1));
        }
        flush.run();
        for (int i = 0; i < ids.size(); i++) {
            setOrder.accept(byId.get(ids.get(i)), i + 1);
        }
        flush.run();
    }
}
