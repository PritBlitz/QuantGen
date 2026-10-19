package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Published after every price tick has committed (symbol → new price), for listeners outside trading such as
 * Autopilot's PRICE_CROSSES trigger, so trading doesn't depend on them.
 */
public record PricesUpdatedEvent(Map<String, BigDecimal> prices) {
}
