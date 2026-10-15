package com.quantgen.common.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Exactly-once effect for money-moving POSTs (F-PAY-2, ARCHITECTURE §4.4).
 *
 * <p>Must run inside the same transaction as the money movement. The key row is inserted first with
 * {@code ON CONFLICT DO NOTHING}; a concurrent request with the same key blocks on the unique index until
 * the first one commits, then replays its stored response. If the first one rolls back (e.g.
 * INSUFFICIENT_FUNDS), its key row disappears with it and a retry executes afresh.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A response body plus whether it was replayed from an earlier request with the same key. */
    public record Result<T>(T body, boolean replayed) {
    }

    /**
     * Runs {@code action} once per (user, key). {@code fingerprint} identifies the request (method, path,
     * body) and is compared on replays; a different fingerprint yields 409 IDEMPOTENCY_CONFLICT.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Result<T> execute(UUID userId, UUID key, String fingerprint, int successStatus, Class<T> responseType,
                                 Supplier<T> action) {
        String requestHash = sha256(fingerprint);
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO idempotency_keys (user_id, idem_key, request_hash)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, idem_key) DO NOTHING
                RETURNING id
                """, UUID.class, userId, key, requestHash);

        if (inserted.isEmpty()) {
            StoredResponse stored = jdbc.queryForObject(
                    "SELECT request_hash, response_status, response_body FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                    (rs, i) -> new StoredResponse(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3)),
                    userId, key);
            if (stored == null || !stored.requestHash().equals(requestHash)) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_CONFLICT,
                        "Idempotency-Key was already used for a different request");
            }
            if (stored.body() == null) {
                // Cannot happen with the key row written in the same transaction; fail safe rather than re-run.
                throw new ApiException(ErrorCode.INVALID_STATE, "The original request is still in progress");
            }
            log.info("Idempotent replay for key {}", key);
            return new Result<>(json.readValue(stored.body(), responseType), true);
        }

        T response = action.get();
        jdbc.update("UPDATE idempotency_keys SET response_status = ?, response_body = ? WHERE id = ?",
                successStatus, json.writeValueAsString(response), inserted.getFirst());
        return new Result<>(response, false);
    }

    /** A canonical fingerprint: method, path and the request DTO serialized with the app's JSON rules. */
    public String fingerprint(String method, String path, Object requestBody) {
        return method + " " + path + "\n" + json.writeValueAsString(requestBody);
    }

    private record StoredResponse(String requestHash, Integer status, String body) {
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
