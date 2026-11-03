package io.github.ilyaslabs.foodstack.notification.core.application;

import java.util.Map;

import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.order.api.OrderApi;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.api.events.OrderAccepted;
import io.github.ilyaslabs.foodstack.order.api.events.OrderCancelled;
import io.github.ilyaslabs.foodstack.order.api.events.OrderConfirmed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderDelivered;
import io.github.ilyaslabs.foodstack.order.api.events.OrderFailed;
import io.github.ilyaslabs.foodstack.order.api.events.OrderPickedUp;
import io.github.ilyaslabs.foodstack.order.api.events.OrderReady;
import io.github.ilyaslabs.foodstack.order.api.events.OrderRejected;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

/**
 * Which order events tell whom what (PRD 4.10). The customer hears about every step that matters
 * to them, in the language they ordered in; the branch's people get "new order" in the operator's
 * language, with a link to their live board.
 */
public class OrderMessages {

    /** Back-office path of the live order board. */
    public static final String BOARD_LINK = "/orders/board";

    private final NotificationService notifications;

    private final Templates templates;

    private final SettingsApi settings;

    private final OrderApi orders;

    private final ServiceCalls serviceCalls;

    private final PiiCipher pii;

    public OrderMessages(NotificationService notifications, Templates templates, SettingsApi settings, OrderApi orders,
            ServiceCalls serviceCalls, PiiCipher pii) {
        this.pii = pii;
        this.notifications = notifications;
        this.templates = templates;
        this.settings = settings;
        this.orders = orders;
        this.serviceCalls = serviceCalls;
    }

    public void onOrderEvent(String eventId, Object event) {
        switch (event) {
            case OrderConfirmed e -> {
                customer(eventId, e.order(), "order-confirmed", Map.of("codeLine", codeLine(e.order(), e.deliveryCodeCipher())));
                int minutes = Math.max(1, this.settings.getOrdering().acceptTimeoutSeconds() / 60);
                String language = this.settings.getLocale().defaultLanguage();
                OrderSummary o = e.order();
                Map<String, Object> params = Templates.params("number", o.number(), "store", o.storeName(), "items",
                        o.itemCount(), "total", Money.format(o.totalMinor(), o.currency()), "minutes", minutes, "fulfilment",
                        this.templates.phrase((o.fulfilment() == Fulfilment.PICKUP) ? "pickup" : "delivery", language, Map.of()));
                for (String owner : o.storeOwnerIds()) {
                    this.notifications.send(new Message(eventId, owner, null, "store-new-order", language, params, BOARD_LINK, true,
                            true, true, false, true));
                }
            }
            case OrderAccepted e -> customer(eventId, e.order(), "order-accepted", Map.of("minutes", e.prepMinutes()));
            case OrderReady e -> {
                if (e.order().fulfilment() == Fulfilment.PICKUP) {
                    customer(eventId, e.order(), "order-ready-pickup", Map.of());
                }
            }
            case OrderPickedUp e -> customer(eventId, e.order(), "order-picked-up", Map.of());
            case OrderDelivered e -> customer(eventId, e.order(), "order-delivered", Map.of());
            case OrderRejected e -> {
                String language = language(e.order());
                String reason = e.automatic() ? this.templates.phrase("noAnswer", language, Map.of()) : e.reason();
                customer(eventId, e.order(), "order-rejected", Map.of("reason", sentence(reason), "refund",
                        refund(e.refundMinor(), e.order(), language)));
            }
            case OrderCancelled e -> {
                if (!e.actor().startsWith("customer:")) {
                    customer(eventId, e.order(), "order-cancelled", Map.of("reason", sentence(e.reason()), "refund",
                            refund(e.refundMinor(), e.order(), language(e.order()))));
                }
            }
            case OrderFailed e -> customer(eventId, e.order(), "order-failed", Map.of());
            default -> {
            }
        }
    }

    public void onRefund(String eventId, RefundSucceeded refund) {
        OrderSummary order = this.serviceCalls.call(() -> this.orders.getSummary(refund.orderId()));
        if (order != null) {
            customer(eventId, order, "refund-issued", Map.of("amount", Money.format(refund.amountMinor(), refund.currency())));
        }
    }

    private void customer(String eventId, OrderSummary order, String template, Map<String, Object> extra) {
        Map<String, Object> params = Templates.params("number", order.number(), "store", order.storeName());
        params.putAll(extra);
        this.notifications.send(new Message(eventId, order.customerId(), null, template, language(order), params,
                "/orders/" + order.orderId(), false, true, true, false, true));
    }

    /** For a delivery that needs one, the code to give the rider (PRD DR-6); otherwise nothing. */
    private String codeLine(OrderSummary order, String codeCipher) {
        return (order.fulfilment() == Fulfilment.DELIVERY && codeCipher != null)
                ? this.templates.phrase("deliveryCode", language(order), Map.of("code", this.pii.decrypt(codeCipher))) : "";
    }

    private String language(OrderSummary order) {
        return (order.language() != null) ? order.language() : this.settings.getLocale().defaultLanguage();
    }

    private String refund(long refundMinor, OrderSummary order, String language) {
        return (refundMinor > 0)
                ? this.templates.phrase("refund", language, Map.of("amount", Money.format(refundMinor, order.currency()))) : "";
    }

    private static String sentence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.matches(".*[.!?۔]$") ? trimmed : trimmed + ".";
    }
}
