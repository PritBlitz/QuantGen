package com.quantgen.common.events;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/** Appends events in the caller's transaction, so an event exists only if the change that caused it committed. */
@Service
public class OutboxService {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String type, UUID aggregateId, Map<String, ?> payload) {
        jdbc.update("INSERT INTO outbox_events (type, aggregate_id, payload) VALUES (?, ?, ?::jsonb)",
                type, aggregateId, json.writeValueAsString(payload));
    }
}
