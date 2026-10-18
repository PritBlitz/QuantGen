package com.quantgen.common.events;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * An event read back from {@code outbox_events}. Payloads carry ids and amounts only, never names,
 * notes or full account numbers (ARCHITECTURE §12.4).
 */
public record OutboxEvent(UUID id, String type, UUID aggregateId, JsonNode payload, Instant createdAt) {

    public static final String MONEY_MOVED = "MONEY_MOVED";
    public static final String HOLD_PLACED = "HOLD_PLACED";
    public static final String HOLD_RELEASED = "HOLD_RELEASED";
}
