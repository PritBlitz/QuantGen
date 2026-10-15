package com.quantgen.common.notification;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (F-NOT-1). {@link #notify} joins the caller's transaction, so a notification
 * exists only if the event that caused it committed.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void notify(UUID userId, NotificationType type, String title, String body) {
        repository.save(new Notification(userId, type, title, body));
    }
}
