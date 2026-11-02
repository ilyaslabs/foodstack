package io.github.ilyaslabs.foodstack.notification.core.application;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

/** Messages about the account itself: welcome. */
public class AccountMessages {

    private final NotificationService notifications;

    private final SettingsApi settings;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    public AccountMessages(NotificationService notifications, SettingsApi settings, IdentityApi identity,
            ServiceCalls serviceCalls) {
        this.notifications = notifications;
        this.settings = settings;
        this.identity = identity;
        this.serviceCalls = serviceCalls;
    }

    public void onRegistered(String eventId, UserRegistered registered) {
        if (registered.roles() == null || !registered.roles().contains(Role.CUSTOMER)) {
            return;
        }
        UserView user = this.serviceCalls.call(() -> this.identity.getUser(registered.userId()));
        if (user == null) {
            return;
        }
        this.notifications.send(new Message(eventId, user.id(), null, "welcome", this.settings.getLocale().defaultLanguage(),
                Templates.params("brand", brand(), "name", (user.displayName() != null) ? user.displayName() : ""), "/", false,
                true, true, true));
    }

    String brand() {
        BrandingView branding = this.settings.getBranding();
        return (branding != null && branding.name() != null) ? branding.name() : "FoodStack";
    }
}
