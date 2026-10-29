package com.quantgen.trading.service;

import java.util.Set;

/**
 * D-047: live prices of {@code symbols} were just moved to real NSE levels in one jump. Published inside the rebase
 * transaction; synchronous listeners (Autopilot pauses its PRICE_CROSSES rules) share that transaction.
 */
public record LivePricesRebasedEvent(Set<String> symbols) {
}
