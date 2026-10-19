package com.quantgen.payment.domain;

/** PENDING_REVIEW: held for fraud review (V2-M6); an admin moves it to COMPLETED or REJECTED. */
public enum TransferStatus {
    COMPLETED, FAILED, PENDING_REVIEW, REJECTED
}
