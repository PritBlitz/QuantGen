package com.quantgen.common.events;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Dispatches pending outbox events to {@link EventConsumer}s (ARCHITECTURE §12.4). Batches are claimed with
 * {@code FOR NO KEY UPDATE SKIP LOCKED}, so more than one instance can poll without double work. (Not plain
 * {@code FOR UPDATE}: each consumer's own transaction inserts a consumer_offsets row whose foreign key needs a
 * KEY SHARE lock on the claimed event, and FOR UPDATE would block it forever.)
 */
@Component
@ConditionalOnProperty(name = "qg.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPoller {

    static final int BATCH = 100;
    public static final int MAX_ATTEMPTS = 5;

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final List<EventConsumer> consumers;
    private final TransactionTemplate batchTx;
    private final TransactionTemplate consumerTx;

    public OutboxPoller(JdbcTemplate jdbc, JsonMapper json, ObjectProvider<EventConsumer> consumers,
                        PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.consumers = consumers.orderedStream().toList(); // may legitimately be empty
        this.batchTx = new TransactionTemplate(txManager);
        // One transaction per (event, consumer): a failing consumer rolls back only its own work, and its
        // consumer_offsets row commits with that work, so a later failure of the batch can't run it twice.
        // (Savepoints would be tidier, but Hibernate's JpaDialect doesn't support them.)
        this.consumerTx = new TransactionTemplate(txManager);
        this.consumerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${qg.outbox.poll-millis:500}")
    public void poll() {
        try {
            while (Boolean.TRUE.equals(batchTx.execute(status -> dispatchBatch() == BATCH))) {
                // a full batch means there may be more: keep draining
            }
        } catch (RuntimeException e) {
            log.error("Outbox poll failed", e);
        }
    }

    /** Claims and dispatches up to one batch; returns how many events it claimed. */
    int dispatchBatch() {
        List<Row> rows = jdbc.query("""
                SELECT id, type, aggregate_id, payload::text AS payload, created_at, attempts
                  FROM outbox_events WHERE processed_at IS NULL
                 ORDER BY created_at, id LIMIT ? FOR NO KEY UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(
                new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("type"),
                        rs.getObject("aggregate_id", UUID.class), json.readTree(rs.getString("payload")),
                        rs.getTimestamp("created_at").toInstant()), rs.getInt("attempts")), BATCH);
        for (Row row : rows) {
            String failure = null;
            for (EventConsumer consumer : consumers) {
                if (!consumer.accepts(row.event().type())) {
                    continue;
                }
                try {
                    consumerTx.executeWithoutResult(status -> deliver(consumer, row.event()));
                } catch (RuntimeException e) {
                    log.warn("Consumer {} failed on event {} ({})", consumer.name(), row.event().id(), e.toString());
                    failure = consumer.name() + ": " + e.getClass().getSimpleName();
                }
            }
            Timestamp now = Timestamp.from(Instant.now());
            if (failure == null) {
                jdbc.update("UPDATE outbox_events SET processed_at = ?, attempts = attempts + 1 WHERE id = ?",
                        now, row.event().id());
            } else if (row.attempts() + 1 >= MAX_ATTEMPTS) {
                log.error("Giving up on outbox event {} after {} attempts: {}", row.event().id(), MAX_ATTEMPTS, failure);
                jdbc.update("UPDATE outbox_events SET processed_at = ?, attempts = attempts + 1, last_error = ? "
                        + "WHERE id = ?", now, failure, row.event().id());
            } else {
                jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = ? WHERE id = ?",
                        failure, row.event().id());
            }
        }
        return rows.size();
    }

    private void deliver(EventConsumer consumer, OutboxEvent event) {
        int inserted = jdbc.update("INSERT INTO consumer_offsets (consumer, event_id) VALUES (?, ?) "
                + "ON CONFLICT DO NOTHING", consumer.name(), event.id());
        if (inserted == 1) {
            consumer.handle(event);
        }
    }

    private record Row(OutboxEvent event, int attempts) {
    }
}
