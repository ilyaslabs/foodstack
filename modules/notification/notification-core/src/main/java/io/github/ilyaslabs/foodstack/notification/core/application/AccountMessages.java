package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.identity.api.events.AccountDeletionScheduled;
import io.github.ilyaslabs.foodstack.identity.api.events.EmailCodeIssued;
import io.github.ilyaslabs.foodstack.identity.api.events.UserRegistered;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

/** Messages about the account itself: welcome, sign-in codes and password reset codes. */
public class AccountMessages {

    private final NotificationService notifications;

    private final SettingsApi settings;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    private final PiiCipher pii;

    private final Clock clock;

    public AccountMessages(NotificationService notifications, SettingsApi settings, IdentityApi identity,
            ServiceCalls serviceCalls, PiiCipher pii, Clock clock) {
        this.pii = pii;
        this.clock = clock;
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

    /**
     * Emails a one-time code. It is required (sent whatever the preferences), email only, and never
     * goes to the notification centre; the delivery log keeps it sealed.
     */
    public void onEmailCode(String eventId, EmailCodeIssued issued) {
        if (issued.expiresAt() != null && !issued.expiresAt().isAfter(this.clock.instant())) {
            return;
        }
        String template = EmailCodeIssued.PASSWORD_RESET.equals(issued.purpose()) ? "password-reset-code" : "login-code";
        long minutes = (issued.expiresAt() != null)
                ? Math.max(1, Duration.between(this.clock.instant(), issued.expiresAt()).toSeconds() / 60) : 5;
        this.notifications.send(new Message(eventId, issued.userId(), null, template, this.settings.getLocale().defaultLanguage(),
                Templates.params("brand", brand(), "code", this.pii.decrypt(issued.codeCipher()), "minutes", minutes), null, false,
                false, true, true));
    }

    /**
     * Tells a customer when their account will be deleted and that signing in cancels it (PRD
     * ID-7). Required: sent whatever the email preferences.
     */
    public void onDeletionScheduled(String eventId, AccountDeletionScheduled scheduled) {
        UserView user = this.serviceCalls.call(() -> this.identity.getUser(scheduled.userId()));
        if (user == null || scheduled.deleteAfter() == null) {
            return;
        }
        LocaleView locale = this.settings.getLocale();
        String language = locale.defaultLanguage();
        ZoneId zone;
        try {
            zone = (locale.timezone() != null) ? ZoneId.of(locale.timezone()) : ZoneOffset.UTC;
        }
        catch (java.time.DateTimeException ex) {
            zone = ZoneOffset.UTC;
        }
        String date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
            .withLocale(Locale.forLanguageTag(language))
            .format(scheduled.deleteAfter().atZone(zone));
        this.notifications.send(new Message(eventId, user.id(), null, "account-deletion-scheduled", language,
                Templates.params("brand", brand(), "name", (user.displayName() != null) ? user.displayName() : "", "date", date),
                null, false, false, true, true));
    }

    String brand() {
        BrandingView branding = this.settings.getBranding();
        return (branding != null && branding.name() != null) ? branding.name() : "FoodStack";
    }
}
