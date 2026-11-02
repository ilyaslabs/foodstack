package io.github.ilyaslabs.foodstack.notification.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.notification.core.application.InboxService;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification centre behind the bell in both apps (PRD NO-1), the user's email choices (NO-3)
 * and, for the operator, the email delivery log with retry (NO-4).
 */
@RestController
class NotificationsController {

    private final InboxService inbox;

    private final NotificationService notifications;

    NotificationsController(InboxService inbox, NotificationService notifications) {
        this.inbox = inbox;
        this.notifications = notifications;
    }

    record PreferencesRequest(Boolean orderEmails, Boolean marketingEmails) {
    }

    @GetMapping("/api/v1/notifications")
    @PreAuthorize("isAuthenticated()")
    List<InboxService.Item> list(@RequestParam(required = false) Long before, @RequestParam(defaultValue = "20") int limit) {
        return this.inbox.list(CurrentUser.id(), before, limit);
    }

    @GetMapping("/api/v1/notifications/unread-count")
    @PreAuthorize("isAuthenticated()")
    Map<String, Long> unread() {
        return Map.of("unread", this.inbox.unread(CurrentUser.id()));
    }

    @PostMapping("/api/v1/notifications/{id}/read")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> read(@PathVariable String id) {
        this.inbox.markRead(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/notifications/read-all")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> readAll() {
        this.inbox.markAllRead(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/notifications/preferences")
    @PreAuthorize("isAuthenticated()")
    NotificationService.Preferences preferences() {
        return this.notifications.preferences(CurrentUser.id());
    }

    @PutMapping("/api/v1/notifications/preferences")
    @PreAuthorize("isAuthenticated()")
    NotificationService.Preferences updatePreferences(@RequestBody PreferencesRequest request) {
        NotificationService.Preferences current = this.notifications.preferences(CurrentUser.id());
        return this.notifications.updatePreferences(CurrentUser.id(),
                (request.orderEmails() != null) ? request.orderEmails() : current.orderEmails(),
                (request.marketingEmails() != null) ? request.marketingEmails() : current.marketingEmails());
    }

    @GetMapping("/api/v1/notifications/deliveries")
    @PreAuthorize("hasAnyAuthority('settings:manage', 'order:manage')")
    List<NotificationService.DeliveryItem> deliveries(@RequestParam(required = false) String status,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int limit) {
        return this.notifications.deliveries(status, before, limit);
    }

    @PostMapping("/api/v1/notifications/deliveries/{id}/retry")
    @PreAuthorize("hasAnyAuthority('settings:manage', 'order:manage')")
    ResponseEntity<Void> retry(@PathVariable String id) {
        this.notifications.retry(id);
        return ResponseEntity.accepted().build();
    }
}
