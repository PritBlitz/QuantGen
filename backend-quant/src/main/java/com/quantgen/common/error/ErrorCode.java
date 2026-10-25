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
    SUSPECTED_FRAUD(HttpStatus.UNPROCESSABLE_CONTENT, "This payment looks unusual and was stopped"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
    INSUFFICIENT_HOLDINGS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient holdings"),
    LOAN_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "Loan application is not eligible"),
    INVALID_STATE(HttpStatus.CONFLICT, "Operation not allowed in the current state"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    STEP_UP_REQUIRED(HttpStatus.UNAUTHORIZED, "Extra verification is required for this action"),
    PIN_LOCKED(HttpStatus.LOCKED, "Transaction PIN is locked"),
    COOLING_PERIOD(HttpStatus.UNPROCESSABLE_CONTENT, "This payee was added recently; the limit for new payees applies"),
    INTENT_EXPIRED(HttpStatus.CONFLICT, "This intent has expired"),
    RULE_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "The rule is not valid"),
    KYC_REQUIRED(HttpStatus.FORBIDDEN, "KYC details are required for this action"),
    FEATURE_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "This feature is currently switched off"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The assistant isn't available right now"),
    BUDGET_EXHAUSTED(HttpStatus.TOO_MANY_REQUESTS, "The assistant's usage limit is reached; try again later"),
    PAYMENT_VERIFICATION_FAILED(HttpStatus.BAD_REQUEST, "The payment could not be verified"),
    GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "The payment gateway is unavailable"),
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
