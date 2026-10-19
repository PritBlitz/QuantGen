package com.quantgen.common.notification;

/** F-NOT-1 notification types. */
public enum NotificationType {
    MONEY_RECEIVED,
    LOAN_APPROVED,
    LOAN_REJECTED,
    EMI_DUE,
    EMI_PAID,
    EMI_OVERDUE,
    ACCOUNT_FROZEN,
    ACCOUNT_UNFROZEN,
    NEW_DEVICE_LOGIN,
    TRANSFER_UNDER_REVIEW,
    PAYMENT_REQUEST,
    SCHEDULED_FAILED,
    FD_MATURED,
    BUDGET_ALERT,
    PRICE_ALERT,
    CARD_DECLINED,
    /** Sent to admins when the nightly seal-chain check finds a break (PRD F-POB-4). */
    LEDGER_INTEGRITY
}
