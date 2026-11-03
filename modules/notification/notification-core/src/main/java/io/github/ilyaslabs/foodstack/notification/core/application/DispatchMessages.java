package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.dispatch.api.events.DispatchAlert;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderAssigned;
import io.github.ilyaslabs.foodstack.dispatch.api.events.RiderOffered;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

/**
 * Which dispatch events tell whom what (PRD DR-3, OF-8, TR-2): a rider's phone rings for an offer,
 * the customer hears who is bringing the order, and support staff are alerted when no rider takes
 * a job.
 */
public class DispatchMessages {

    /** Back-office path of the rider's screen. */
    public static final String RIDER_LINK = "/rider";

    /** Back-office path of the dispatch board. */
    public static final String DISPATCH_LINK = "/dispatch";

    private final NotificationService notifications;

    private final SettingsApi settings;

    private final IdentityApi identity;

    private final ServiceCalls serviceCalls;

    public DispatchMessages(NotificationService notifications, SettingsApi settings, IdentityApi identity,
            ServiceCalls serviceCalls) {
        this.notifications = notifications;
        this.settings = settings;
        this.identity = identity;
        this.serviceCalls = serviceCalls;
    }

    /** Push only: an offer is gone in seconds, so it has no place in the notification centre. */
    public void onOffered(String eventId, RiderOffered e) {
        DeliverySummary d = e.delivery();
        this.notifications.send(new Message(eventId, e.riderId(), null, "rider-offer", operatorLanguage(),
                Templates.params("number", d.orderNumber(), "store", d.storeName(), "earning",
                        Money.format(e.earningMinor(), e.currency())),
                RIDER_LINK, true, false, false, false, true));
    }

    public void onAssigned(String eventId, RiderAssigned e) {
        DeliverySummary d = e.delivery();
        String language = (d.language() != null) ? d.language() : operatorLanguage();
        this.notifications.send(new Message(eventId, d.customerId(), null, "rider-assigned", language,
                Templates.params("number", d.orderNumber(), "store", d.storeName(), "rider", e.riderName()),
                "/orders/" + d.orderId(), false, true, false, false, true));
        boolean riderTookIt = e.actor() != null && e.actor().equals("rider:" + e.riderId());
        if (!riderTookIt) {
            // a store or support gave the rider this job: tell the rider
            this.notifications.send(new Message(eventId, e.riderId(), null, "rider-job-assigned", operatorLanguage(),
                    Templates.params("number", d.orderNumber(), "store", d.storeName()), RIDER_LINK, true, true, false, false,
                    true));
        }
    }

    public void onAlert(String eventId, DispatchAlert e) {
        DeliverySummary d = e.delivery();
        List<String> staff = this.serviceCalls.call(() -> this.identity.userIdsWithRoles(Set.of(Role.ADMIN, Role.SUPPORT)));
        Map<String, Object> params = Templates.params("number", d.orderNumber(), "store", d.storeName(), "minutes",
                Math.max(1, e.searchingSeconds() / 60));
        for (String userId : staff) {
            this.notifications.send(new Message(eventId, userId, null, "dispatch-alert", operatorLanguage(), params,
                    DISPATCH_LINK, true, true, false, false, true));
        }
    }

    private String operatorLanguage() {
        return this.settings.getLocale().defaultLanguage();
    }
}
