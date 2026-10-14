package com.counterline.pos.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Stable page shape for clients (Spring's PageImpl JSON is not a supported contract). */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
