package com.quantgen.auth.security;

/** What a step-up token is for (PRD 12.14). A token for one action never authorises another. */
public enum StepUpAction {
    TRANSFER,
    INTENT_APPROVE,
    RULE_ACTIVATE,
    CARD_REVEAL,
    SECURITY_SETTINGS,
    FD_CLOSE,
    TOKEN_CREATE
}
