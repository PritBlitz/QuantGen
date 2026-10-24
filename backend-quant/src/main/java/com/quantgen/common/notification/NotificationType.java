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
    /** An Autopilot run failed, partly failed or was skipped over a cap; or a NOTIFY action (V2-M5). */
    RULE_RUN,
    /** An Autopilot rule was paused after repeated failures (V2-M5). */
    RULE_PAUSED,
    /** Sent to admins when the nightly seal-chain check finds a break (PRD F-POB-4). */
    LEDGER_INTEGRITY,
    /** An admin refunded or rejected a card dispute (F-CARD-4). */
    CARD_DISPUTE_RESOLVED
}
