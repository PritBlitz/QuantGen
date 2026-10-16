package com.quantgen.common.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.error.ApiException;

/**
 * In-app notifications (F-NOT-1). {@link #notify} joins the caller's transaction, so a notification
 * exists only if the event that caused it committed. Reads are scoped to the owner: someone else's
 * notification is NOT_FOUND.
 */
@Service
public class NotificationService {

    /** PRD §7.8: the list is capped at the most recent 50. */
    public static final int LIST_LIMIT = 50;

    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record NotificationDto(UUID id, NotificationType type, String title, String body, boolean read,
                                  Instant createdAt) {
    }

    @Transactional
    public void notify(UUID userId, NotificationType type, String title, String body) {
        repository.save(new Notification(userId, type, title, body));
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> list(UUID userId, boolean unreadOnly) {
        PageRequest first = PageRequest.of(0, LIST_LIMIT);
        List<Notification> rows = unreadOnly
                ? repository.findByUserIdAndReadAtIsNullOrderByCreatedAtDesc(userId, first)
                : repository.findByUserIdOrderByCreatedAtDesc(userId, first);
        return rows.stream().map(NotificationService::toDto).toList();
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    /** Idempotent: marking an already-read notification again is a no-op. */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> ApiException.notFound("Notification"));
        n.markRead(clock.instant());
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repository.markAllRead(userId, clock.instant());
    }

    private static NotificationDto toDto(Notification n) {
        return new NotificationDto(n.getId(), NotificationType.valueOf(n.getType()), n.getTitle(), n.getBody(),
                n.getReadAt() != null, n.getCreatedAt());
    }
}
