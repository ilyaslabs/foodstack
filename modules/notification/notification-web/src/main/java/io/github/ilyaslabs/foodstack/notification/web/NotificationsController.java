package io.github.ilyaslabs.foodstack.notification.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.notification.core.application.CustomerNotificationData;
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

    private final CustomerNotificationData data;

    NotificationsController(InboxService inbox, NotificationService notifications, CustomerNotificationData data) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.data = data;
    }

    /** The user's notification data, for their data export (PRD ID-7). */
    @Flow(value = "notification-export", label = "Export my notifications", description = "Personal data export")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/mine/export", caller = "Customer app")
    @GetMapping("/api/v1/notifications/mine/export")
    @PreAuthorize("isAuthenticated()")
    CustomerNotificationData.Export export() {
        return this.data.export(CurrentUser.id());
    }

    record PreferencesRequest(Boolean orderEmails, Boolean marketingEmails) {
    }

    @Flow(value = "notification-inbox", label = "My notifications", description = "Newest first")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications", caller = "Customer app, back office, rider app")
    @GetMapping("/api/v1/notifications")
    @PreAuthorize("isAuthenticated()")
    List<InboxService.Item> list(@RequestParam(required = false) Long before, @RequestParam(defaultValue = "20") int limit) {
        return this.inbox.list(CurrentUser.id(), before, limit);
    }

    @Flow(value = "notification-unread", label = "Unread count", description = "")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/unread-count", caller = "Customer app, back office, rider app")
    @GetMapping("/api/v1/notifications/unread-count")
    @PreAuthorize("isAuthenticated()")
    Map<String, Long> unread() {
        return Map.of("unread", this.inbox.unread(CurrentUser.id()));
    }

    @Flow(value = "notification-read", label = "Mark one read", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/notifications/{id}/read", caller = "Customer app, back office, rider app")
    @PostMapping("/api/v1/notifications/{id}/read")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> read(@PathVariable String id) {
        this.inbox.markRead(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    @Flow(value = "notification-read-all", label = "Mark all read", description = "")
    @EVL.Endpoint(method = "POST", path = "/api/v1/notifications/read-all", caller = "Customer app, back office, rider app")
    @PostMapping("/api/v1/notifications/read-all")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> readAll() {
        this.inbox.markAllRead(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @Flow(value = "notification-preferences-view", label = "My email preferences", description = "")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/preferences", caller = "Customer app, back office, rider app")
    @GetMapping("/api/v1/notifications/preferences")
    @PreAuthorize("isAuthenticated()")
    NotificationService.Preferences preferences() {
        return this.notifications.preferences(CurrentUser.id());
    }

    @Flow(value = "notification-preferences-update", label = "Change my email preferences", description = "Order and marketing emails")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/notifications/preferences", caller = "Customer app, back office, rider app")
    @PutMapping("/api/v1/notifications/preferences")
    @PreAuthorize("isAuthenticated()")
    NotificationService.Preferences updatePreferences(@RequestBody PreferencesRequest request) {
        NotificationService.Preferences current = this.notifications.preferences(CurrentUser.id());
        return this.notifications.updatePreferences(CurrentUser.id(),
                (request.orderEmails() != null) ? request.orderEmails() : current.orderEmails(),
                (request.marketingEmails() != null) ? request.marketingEmails() : current.marketingEmails());
    }

    @Flow(value = "notification-deliveries", label = "Email and push log", description = "By status, newest first")
    @EVL.Endpoint(method = "GET", path = "/api/v1/notifications/deliveries", caller = "Back office (admin)")
    @GetMapping("/api/v1/notifications/deliveries")
    @PreAuthorize("hasAnyAuthority('settings:manage', 'order:manage')")
    List<NotificationService.DeliveryItem> deliveries(@RequestParam(required = false) String status,
            @RequestParam(required = false) Long before, @RequestParam(defaultValue = "50") int limit) {
        return this.notifications.deliveries(status, before, limit);
    }

    @Flow(value = "notification-delivery-retry", label = "Send a failed email again", description = "Never an expired code")
    @EVL.Endpoint(method = "POST", path = "/api/v1/notifications/deliveries/{id}/retry", caller = "Back office (admin)")
    @PostMapping("/api/v1/notifications/deliveries/{id}/retry")
    @PreAuthorize("hasAnyAuthority('settings:manage', 'order:manage')")
    ResponseEntity<Void> retry(@PathVariable String id) {
        this.notifications.retry(id);
        return ResponseEntity.accepted().build();
    }
}
