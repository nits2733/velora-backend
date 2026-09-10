package com.velora.backend.controller.notification;

import com.velora.backend.dto.notification.NotificationResponse;
import com.velora.backend.dto.notification.UnreadCountResponse;
import com.velora.backend.security.UserPrincipal;
import com.velora.backend.service.notification.NotificationService;
import com.velora.backend.util.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app notification inbox")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 50;

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "List the current user's notifications, newest first")
    public ResponseEntity<PageResponse<NotificationResponse>> list(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));

        return ResponseEntity.ok(notificationService.list(principal.getId(), pageable));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get the current user's unread notification count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(new UnreadCountResponse(notificationService.unreadCount(principal.getId())));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark one of the current user's notifications as read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        notificationService.markRead(principal.getId(), id);
        return ResponseEntity.noContent().build();
    }
}
