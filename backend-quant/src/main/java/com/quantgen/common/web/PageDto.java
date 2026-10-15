package com.quantgen.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import com.quantgen.common.error.ApiException;

/** The PRD §7.1 page envelope. */
public record PageDto<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static <S, T> PageDto<T> of(Page<S> page, Function<S, T> mapper) {
        return new PageDto<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** Validated paging parameters: page ≥ 0, 1 ≤ size ≤ 100 (unsorted; queries define their order). */
    public static Pageable pageable(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "page", "must be at least 0");
        }
        if (s < 1 || s > MAX_SIZE) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "size", "must be between 1 and " + MAX_SIZE);
        }
        return PageRequest.of(p, s);
    }
}
