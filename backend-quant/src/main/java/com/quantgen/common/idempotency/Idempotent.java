package com.quantgen.common.idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Controller helpers for 🔒💰 endpoints. */
public final class Idempotent {

    public static final String HEADER = "Idempotency-Key";
    /** Set on responses replayed from an earlier request with the same key. */
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private Idempotent() {
    }

    public static <T> ResponseEntity<T> created(IdempotencyService.Result<T> result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }

    /** For 🔒💰 actions on an existing resource that answer 200. */
    public static <T> ResponseEntity<T> ok(IdempotencyService.Result<T> result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (result.replayed()) {
            builder.header(REPLAYED_HEADER, "true");
        }
        return builder.body(result.body());
    }
}
