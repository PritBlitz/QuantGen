package com.quantgen.payment.domain;

/** PRD 12.2 TransferDto.source: what started the transfer. */
public enum TransferSource {
    MANUAL, SCHEDULED, AUTOPILOT, INTENT, REQUEST
}
