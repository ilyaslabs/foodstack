package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditLimitExceeded;
import io.github.ilyaslabs.foodstack.ledger.api.events.StoreCreditRestored;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

/**
 * Money messages for the back office (PRD DP-7): a branch that collects its own card payments went
 * over its credit limit, so the operator collects again until it settles; the branch's owners and
 * finance are told, in the notification centre and by email. Getting back within the limit is told
 * to the owners in the notification centre.
 */
public class FinanceMessages {

    /** Back-office path of the payouts and finance screen. */
    public static final String FINANCE_LINK = "/finance";

    private final NotificationService notifications;

    private final SettingsApi settings;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    public FinanceMessages(NotificationService notifications, SettingsApi settings, IdentityApi identity,
            ServiceCalls serviceCalls) {
        this.notifications = notifications;
        this.settings = settings;
        this.identity = identity;
        this.serviceCalls = serviceCalls;
    }

    public void onExceeded(String eventId, StoreCreditLimitExceeded e) {
        Map<String, Object> params = Templates.params("store", name(e.storeName()), "owed",
                Money.format(e.owedMinor(), e.currency()), "limit", Money.format(e.limitMinor(), e.currency()));
        Set<String> people = new LinkedHashSet<>((e.ownerUserIds() != null) ? e.ownerUserIds() : Set.of());
        List<String> staff = this.serviceCalls.call(() -> this.identity.userIdsWithRoles(Set.of(Role.ADMIN, Role.FINANCE)));
        people.addAll(staff);
        for (String userId : people) {
            this.notifications.send(new Message(eventId, userId, null, "credit-limit-exceeded", language(), params,
                    FINANCE_LINK, true, true, true, true));
        }
    }

    public void onRestored(String eventId, StoreCreditRestored e) {
        Map<String, Object> params = Templates.params("store", name(e.storeName()));
        for (String userId : (e.ownerUserIds() != null) ? e.ownerUserIds() : Set.<String>of()) {
            this.notifications.send(new Message(eventId, userId, null, "credit-limit-restored", language(), params,
                    FINANCE_LINK, true, true, false, false));
        }
    }

    private static String name(String storeName) {
        return (storeName != null) ? storeName : "Your branch";
    }

    private String language() {
        return this.settings.getLocale().defaultLanguage();
    }
}
