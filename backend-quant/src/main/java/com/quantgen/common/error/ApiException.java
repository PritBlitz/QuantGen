package com.quantgen.common.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * A business error that maps directly to the PRD §7.1 error body. The message is shown to the
 * client, so it must never contain internal details.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final HttpStatus status;
    private final List<ApiError.FieldError> fieldErrors;
    private final Long retryAfterSeconds;
    private final ApiError.StepUpChallenge stepUp;

    public ApiException(ErrorCode code) {
        this(code, code.defaultMessage());
    }

    public ApiException(ErrorCode code, String message) {
        this(code, code.status(), message, List.of(), null, null);
    }

    private ApiException(ErrorCode code, HttpStatus status, String message,
                         List<ApiError.FieldError> fieldErrors, Long retryAfterSeconds,
                         ApiError.StepUpChallenge stepUp) {
        super(message, null, false, false);
        this.code = code;
        this.status = status;
        this.fieldErrors = List.copyOf(fieldErrors);
        this.retryAfterSeconds = retryAfterSeconds;
        this.stepUp = stepUp;
    }

    public static ApiException notFound(String what) {
        return new ApiException(ErrorCode.NOT_FOUND, what + " not found");
    }

    /** A validation failure on a single field, with an explicit status (e.g. 409 for a duplicate). */
    public static ApiException fieldError(HttpStatus status, String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, status, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                List.of(new ApiError.FieldError(field, message)), null, null);
    }

    public static ApiException rateLimited(String message, long retryAfterSeconds) {
        return new ApiException(ErrorCode.RATE_LIMITED, ErrorCode.RATE_LIMITED.status(), message, List.of(),
                Math.max(1, retryAfterSeconds), null);
    }

    /** 401 STEP_UP_REQUIRED: the client completes a step-up and retries with X-Step-Up-Token. */
    public static ApiException stepUpRequired(List<String> methods, String action) {
        return new ApiException(ErrorCode.STEP_UP_REQUIRED, ErrorCode.STEP_UP_REQUIRED.status(),
                ErrorCode.STEP_UP_REQUIRED.defaultMessage(), List.of(), null,
                new ApiError.StepUpChallenge(List.copyOf(methods), action));
    }

    /** 423 PIN_LOCKED with Retry-After. */
    public static ApiException pinLocked(long retryAfterSeconds) {
        return new ApiException(ErrorCode.PIN_LOCKED, ErrorCode.PIN_LOCKED.status(),
                "Too many wrong PIN attempts. Try again later or use a passkey.", List.of(),
                Math.max(1, retryAfterSeconds), null);
    }

    public ErrorCode code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public List<ApiError.FieldError> fieldErrors() {
        return fieldErrors;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public ApiError.StepUpChallenge stepUp() {
        return stepUp;
    }
}
