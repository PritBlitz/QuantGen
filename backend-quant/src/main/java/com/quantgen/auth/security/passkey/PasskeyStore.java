package com.quantgen.auth.security.passkey;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

/**
 * Storage for the Yubico library: credentials by id, user handles, and the email-as-username mapping. All
 * lookups are by exact key; nothing here decides who may do what.
 */
@Component
class PasskeyStore implements CredentialRepository {

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    PasskeyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ CredentialRepository

    @Override
    @Transactional(readOnly = true)
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        List<byte[]> ids = jdbc.queryForList("""
                SELECT p.credential_id FROM passkeys p JOIN users u ON u.id = p.user_id WHERE u.email = ?
                """, byte[].class, username);
        Set<PublicKeyCredentialDescriptor> out = new HashSet<>();
        for (byte[] id : ids) {
            out.add(PublicKeyCredentialDescriptor.builder().id(new ByteArray(id)).build());
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return jdbc.queryForList("""
                SELECT w.user_handle FROM webauthn_users w JOIN users u ON u.id = w.user_id WHERE u.email = ?
                """, byte[].class, username).stream().findFirst().map(ByteArray::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return jdbc.queryForList("""
                SELECT u.email FROM webauthn_users w JOIN users u ON u.id = w.user_id WHERE w.user_handle = ?
                """, String.class, userHandle.getBytes()).stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return lookupAll(credentialId).stream()
                .filter(c -> c.getUserHandle().equals(userHandle))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return new HashSet<>(jdbc.query("""
                SELECT p.credential_id, p.public_key_cose, p.sign_count, w.user_handle
                  FROM passkeys p JOIN webauthn_users w ON w.user_id = p.user_id
                 WHERE p.credential_id = ?
                """, (rs, i) -> RegisteredCredential.builder()
                .credentialId(new ByteArray(rs.getBytes("credential_id")))
                .userHandle(new ByteArray(rs.getBytes("user_handle")))
                .publicKeyCose(new ByteArray(rs.getBytes("public_key_cose")))
                .signatureCount(rs.getLong("sign_count"))
                .build(), credentialId.getBytes()));
    }

    // ------------------------------------------------------------------ users and credentials

    /** The user's WebAuthn handle, created on first use. Locks the user's row so concurrent calls agree. */
    @Transactional
    ByteArray userHandle(UUID userId) {
        List<byte[]> existing = jdbc.queryForList("SELECT user_handle FROM webauthn_users WHERE user_id = ?",
                byte[].class, userId);
        if (!existing.isEmpty()) {
            return new ByteArray(existing.getFirst());
        }
        byte[] handle = new byte[32];
        random.nextBytes(handle);
        jdbc.update("INSERT INTO webauthn_users (user_id, user_handle) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, handle);
        return new ByteArray(jdbc.queryForObject("SELECT user_handle FROM webauthn_users WHERE user_id = ?",
                byte[].class, userId));
    }

    @Transactional(readOnly = true)
    Optional<UUID> userIdForHandle(ByteArray handle) {
        return jdbc.queryForList("SELECT user_id FROM webauthn_users WHERE user_handle = ?", UUID.class,
                handle.getBytes()).stream().findFirst();
    }

    @Transactional(readOnly = true)
    int count(UUID userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM passkeys WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }

    /** Serializes registrations per user so the 5-passkey cap holds under concurrency. */
    @Transactional
    void lockUser(UUID userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
    }

    @Transactional
    UUID insert(UUID userId, ByteArray credentialId, ByteArray publicKeyCose, long signCount, String name,
                String transports) {
        return jdbc.queryForObject("""
                INSERT INTO passkeys (user_id, credential_id, public_key_cose, sign_count, name, transports)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, credentialId.getBytes(), publicKeyCose.getBytes(), signCount, name, transports);
    }

    @Transactional
    void touch(ByteArray credentialId, long signCount) {
        jdbc.update("UPDATE passkeys SET sign_count = ?, last_used_at = now() WHERE credential_id = ?",
                signCount, credentialId.getBytes());
    }

    @Transactional(readOnly = true)
    List<PasskeyService.PasskeyDto> list(UUID userId) {
        return jdbc.query("SELECT id, name, created_at, last_used_at FROM passkeys WHERE user_id = ? "
                        + "ORDER BY created_at, id",
                (rs, i) -> new PasskeyService.PasskeyDto(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()),
                userId);
    }

    @Transactional(readOnly = true)
    Optional<PasskeyService.PasskeyDto> find(UUID userId, ByteArray credentialId) {
        return jdbc.query("SELECT id, name, created_at, last_used_at FROM passkeys WHERE user_id = ? "
                        + "AND credential_id = ?",
                (rs, i) -> new PasskeyService.PasskeyDto(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("last_used_at") == null ? null : rs.getTimestamp("last_used_at").toInstant()),
                userId, credentialId.getBytes()).stream().findFirst();
    }

    @Transactional
    boolean delete(UUID userId, UUID passkeyId) {
        return jdbc.update("DELETE FROM passkeys WHERE id = ? AND user_id = ?", passkeyId, userId) > 0;
    }

    // ------------------------------------------------------------------ challenges

    @Transactional
    void saveChallenge(String challenge, String purpose, UUID userId, String action, String requestJson,
                       Instant expiresAt) {
        jdbc.update("DELETE FROM webauthn_challenges WHERE expires_at < now()");
        jdbc.update("INSERT INTO webauthn_challenges (challenge, purpose, user_id, action, request_json, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", challenge, purpose, userId, action, requestJson,
                java.sql.Timestamp.from(expiresAt));
    }

    record StoredChallenge(UUID userId, String action, String requestJson) {
    }

    /** Takes the challenge if it exists, matches the purpose and has not expired. It can be taken only once. */
    @Transactional
    Optional<StoredChallenge> takeChallenge(String challenge, String purpose, Instant now) {
        return jdbc.query("""
                DELETE FROM webauthn_challenges WHERE challenge = ? AND purpose = ? AND expires_at > ?
                RETURNING user_id, action, request_json
                """, (rs, i) -> new StoredChallenge(rs.getObject("user_id", UUID.class), rs.getString("action"),
                rs.getString("request_json")), challenge, purpose, java.sql.Timestamp.from(now)).stream().findFirst();
    }
}
