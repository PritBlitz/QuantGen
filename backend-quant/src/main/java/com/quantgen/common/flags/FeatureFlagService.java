package com.quantgen.common.flags;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;

/**
 * F-FLAG-1: switches a feature off without a deploy. Services call {@link #isEnabled}; a switched-off
 * feature answers 503 FEATURE_DISABLED. The set of flags is fixed by the migration, so a typo in a name
 * can never create a flag.
 */
@Service
public class FeatureFlagService {

    public record FlagDto(String name, boolean enabled, Instant updatedAt, UUID updatedBy) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public FeatureFlagService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String name) {
        List<Boolean> found = jdbc.queryForList("SELECT enabled FROM feature_flags WHERE name = ?", Boolean.class, name);
        return !found.isEmpty() && Boolean.TRUE.equals(found.getFirst());
    }

    /** The map the UI uses to hide switched-off features, in a stable order. */
    @Transactional(readOnly = true)
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> flags = new LinkedHashMap<>();
        jdbc.query("SELECT name, enabled FROM feature_flags ORDER BY name",
                rs -> {
                    flags.put(rs.getString("name"), rs.getBoolean("enabled"));
                });
        return flags;
    }

    @Transactional(readOnly = true)
    public List<FlagDto> listAll() {
        return jdbc.query("SELECT name, enabled, updated_at, updated_by FROM feature_flags ORDER BY name",
                (rs, i) -> new FlagDto(rs.getString("name"), rs.getBoolean("enabled"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getObject("updated_by", UUID.class)));
    }

    @Transactional
    public FlagDto set(UUID adminId, String name, boolean enabled) {
        int updated = jdbc.update("UPDATE feature_flags SET enabled = ?, updated_at = now(), updated_by = ? "
                + "WHERE name = ?", enabled, adminId, name);
        if (updated == 0) {
            throw ApiException.notFound("Feature flag");
        }
        audit.record(adminId, "FEATURE_FLAG_SET", "FEATURE_FLAG", name, "enabled=" + enabled);
        return listAll().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow();
    }
}
