package com.quantgen.common.error;

import org.springframework.http.HttpStatus;

/** Error codes from PRD §7.1, each with its default HTTP status. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds"),
    ACCOUNT_FROZEN(HttpStatus.UNPROCESSABLE_CONTENT, "Account is frozen"),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Limit exceeded"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
    INSUFFICIENT_HOLDINGS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient holdings"),
    LOAN_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Loan application is not eligible"),
    INVALID_STATE(HttpStatus.CONFLICT, "Operation not allowed in the current state"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
