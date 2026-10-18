package com.quantgen.common.events;

/**
 * A consumer of outbox events. Delivery is at least once, but the poller records {@code (name, eventId)}
 * in the same transaction as {@link #handle}, so a redelivered event is skipped. Throwing rolls back only
 * this consumer's work for this event; the event is retried (up to {@link OutboxPoller#MAX_ATTEMPTS}).
 */
public interface EventConsumer {

    /** Stable, unique, at most 40 characters: it is the idempotency key's namespace. */
    String name();

    /** Whether this consumer wants the event type. */
    boolean accepts(String eventType);

    void handle(OutboxEvent event);
}
