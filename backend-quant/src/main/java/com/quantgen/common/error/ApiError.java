package com.quantgen.common.error;

import java.time.Instant;
import java.util.List;

/** The standard error body (PRD §7.1), returned for every non-2xx response. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors,
        String requestId) {

    public record FieldError(String field, String message) {
    }
}
