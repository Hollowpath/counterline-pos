package com.counterline.pos.catalog;

import com.counterline.pos.common.BadRequestException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

/**
 * Reorders rows that share a unique sort order (unique per parent, not deferrable). Only the rows whose place changes
 * are written: they first move to a free negative position, then to their final one, so no step collides. A request
 * for the order the rows already have writes nothing, so it changes no row's version.
 *
 * <p>The request carries the version of every row it was made from. The positions of a row only change together with
 * its version, so a list made before another admin edited or reordered any of its rows is refused (409, the usual
 * concurrent-change answer) instead of putting their positions back.
 */
final class SortOrders {

    private SortOrders() {
    }

    /**
     * @param current   every row of the parent, in any order
     * @param ids       the same rows' ids in the order wanted; anything else is a bad request (400)
     * @param versions  the version of each of those rows, in the same order, as the list was loaded; a row saved since is a 409
     * @param flush     writes pending changes (called between the two steps)
     * @param type      the kind of row, for the concurrent-change answer
     */
    static <T> void apply(List<T> current, List<Long> ids, List<Long> versions, Function<T, Long> idOf,
                          ToLongFunction<T> versionOf, ToIntFunction<T> orderOf, BiConsumer<T, Integer> setOrder,
                          Runnable flush, String what, Class<?> type) {
        if (ids == null) {
            throw new BadRequestException("List the " + what + " in the order wanted");
        }
        Set<Long> wanted = new HashSet<>(ids);
        Set<Long> existing = current.stream().map(idOf).collect(Collectors.toSet());
        if (wanted.size() != ids.size() || !wanted.equals(existing)) {
            throw new BadRequestException("The order must list every one of the " + existing.size() + " " + what
                    + " exactly once");
        }
        if (versions == null || versions.size() != ids.size() || versions.contains(null)) {
            throw new BadRequestException("List the version of every one of the " + what + " with the order");
        }
        Map<Long, T> byId = current.stream().collect(Collectors.toMap(idOf, Function.identity()));
        for (int i = 0; i < ids.size(); i++) {
            if (versions.get(i) != versionOf.applyAsLong(byId.get(ids.get(i)))) {
                throw new ObjectOptimisticLockingFailureException(type, ids.get(i));
            }
        }
        // Already in this order (whatever the gaps between the positions): nothing to write.
        List<Long> inPlace = current.stream().sorted(Comparator.comparingInt(orderOf)).map(idOf).toList();
        if (inPlace.equals(ids)) {
            return;
        }
        // A row already at the position it is going to keeps it, and so keeps its version.
        List<Integer> moving = new java.util.ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (orderOf.applyAsInt(byId.get(ids.get(i))) != i + 1) {
                moving.add(i);
            }
        }
        for (int i : moving) {
            setOrder.accept(byId.get(ids.get(i)), -(i + 1));
        }
        flush.run();
        for (int i : moving) {
            setOrder.accept(byId.get(ids.get(i)), i + 1);
        }
        flush.run();
    }
}
