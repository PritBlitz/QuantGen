package com.quantgen.common.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 40)
    private String type;

    @Column(nullable = false, updatable = false, length = 120)
    private String title;

    @Column(nullable = false, updatable = false, length = 500)
    private String body;

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    Notification(UUID userId, NotificationType type, String title, String body) {
        this.userId = userId;
        this.type = type.name();
        this.title = title;
        this.body = body;
    }

    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }
}
