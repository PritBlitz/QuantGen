package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * F-LED-8: {@code ledgerSum} is Σ debits − Σ credits over every line (must be 0); a mismatch is an
 * account whose cached balance differs from the sum of its lines.
 */
public record ReconcileReport(boolean balanced, BigDecimal ledgerSum, List<Mismatch> mismatches) {

    public record Mismatch(UUID ledgerAccountId, String code, BigDecimal cachedBalance, BigDecimal computedBalance) {
    }
}
