package com.quantgen.common.error;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The standard error body (PRD §7.1), returned for every non-2xx response. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors,
        String requestId,
        @JsonInclude(JsonInclude.Include.NON_NULL) StepUpChallenge stepUp) {

    public ApiError(Instant timestamp, int status, String code, String message, String path,
                    List<FieldError> fieldErrors, String requestId) {
        this(timestamp, status, code, message, path, fieldErrors, requestId, null);
    }

    /** Extra body on 401 STEP_UP_REQUIRED (PRD 12.1): which methods can satisfy it and for which action. */
    public record StepUpChallenge(List<String> methods, String action) {
    }

    public record FieldError(String field, String message) {
    }
}
