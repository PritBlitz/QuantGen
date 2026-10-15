package com.quantgen.common.audit;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.web.ClientInfo;

/**
 * Writes the audit trail for sensitive actions (who, what, target, IP, user agent, request id). Joins
 * the caller's transaction, so an audited action and its audit row commit or roll back together.
 * Details must never contain passwords, tokens or full account numbers.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
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
}
