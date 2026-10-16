package com.quantgen.common.notification;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.notification.NotificationService.NotificationDto;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.8 notifications. */
@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "The 50 most recent notifications, newest first")
    public List<NotificationDto> list(@AuthenticationPrincipal AuthUser user,
                                      @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notifications.list(user.id(), unreadOnly);
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal AuthUser user) {
        return Map.of("count", notifications.unreadCount(user.id()));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        notifications.markRead(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal AuthUser user) {
        notifications.markAllRead(user.id());
        return ResponseEntity.noContent().build();
    }
}
