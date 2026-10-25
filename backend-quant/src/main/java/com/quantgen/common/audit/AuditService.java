package com.quantgen.common.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.web.ClientInfo;
import com.quantgen.common.web.PageDto;

/**
 * Writes the audit trail for sensitive actions (who, what, target, IP, user agent, request id). Joins
 * the caller's transaction, so an audited action and its audit row commit or roll back together.
 * Details must never contain passwords, tokens or full account numbers.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final JdbcTemplate jdbc;

    public AuditService(AuditLogRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** PRD §7.7 AuditLogDto. */
    public record AuditLogDto(UUID id, UUID actorId, String actorEmail, String action, String targetType,
                              String targetId, String details, String ip, String requestId, Instant createdAt) {
    }

    @Transactional
    public void record(UUID actorUserId, String actorEmail, String action, String targetType, String targetId,
                       String details) {
        ClientInfo client = ClientInfo.current();
        repository.save(new AuditLog(actorUserId, actorEmail, action, targetType, targetId, details,
                client.ip(), client.userAgent(), client.requestId()));
    }

    @Transactional
    public void record(UUID actorUserId, String action, String targetType, String targetId, String details) {
        record(actorUserId, null, action, targetType, targetId, details);
    }

    /** Commits on its own: for a refusal whose surrounding transaction rolls back (e.g. a stopped payment). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAlways(UUID actorUserId, String action, String targetType, String targetId, String details) {
        record(actorUserId, null, action, targetType, targetId, details);
    }

    /**
     * F-ADM-5 viewer, newest first. {@code actor} is a case-insensitive email substring, {@code action} an
     * exact match. The actor's email falls back to the user's current email when the row didn't store one.
     * This read model joins {@code users} directly; it never writes there.
     */
    @Transactional(readOnly = true)
    public PageDto<AuditLogDto> search(String actor, String action, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (actor != null && !actor.isBlank()) {
            where.append(" AND coalesce(a.actor_email, u.email) ILIKE ? ESCAPE '\\'");
            args.add("%" + escapeLike(actor.trim().toLowerCase()) + "%");
        }
        if (action != null && !action.isBlank()) {
            where.append(" AND a.action = ?");
            args.add(action.trim());
        }
        String from = " FROM audit_logs a LEFT JOIN users u ON u.id = a.actor_user_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<AuditLogDto> rows = jdbc.query("""
                SELECT a.id, a.actor_user_id, coalesce(a.actor_email, u.email) AS actor_email, a.action,
                       a.target_type, a.target_id, a.details, a.ip, a.request_id, a.created_at
                """ + from + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new AuditLogDto(rs.getObject("id", UUID.class), rs.getObject("actor_user_id", UUID.class),
                        rs.getString("actor_email"), rs.getString("action"), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("details"), rs.getString("ip"),
                        rs.getString("request_id"), rs.getTimestamp("created_at").toInstant()),
                pageArgs.toArray());

        long count = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(rows, pageable.getPageNumber(), size, count, (int) ((count + size - 1) / size));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
