package io.github.ilyaslabs.foodstack.order.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An order in {@code order_orders} (shard key {@code { _id: hashed }}, TDD 6.3), the only source of
 * truth. Everything the order needs is copied in at placement (prices, store name, commission), so
 * later menu or commission changes never alter it (TDD 5.3). The status only moves through
 * conditional updates; {@code revision} counts changes. The timeline has one entry per step, so it
 * stays small.
 *
 * @param id order id (UUIDv7)
 * @param number short number people read out, such as {@code 7F3A-91C2}
 * @param customerId who ordered
 * @param customerName the customer's name when ordering
 * @param contactPhone phone the customer gave at checkout, encrypted (PII), or {@code null}
 * @param store the branch as it was when ordering
 * @param status current status
 * @param fulfilment delivery or pickup
 * @param deliveryMode who delivers (delivery orders), fixed at placement
 * @param destination where to deliver, or {@code null} for pickup
 * @param paymentMethod cash or card
 * @param currency ISO 4217 code
 * @param language the customer's language, for messages
 * @param lines what was ordered, priced
 * @param totals money, in minor units
 * @param coupon the coupon used, or {@code null}
 * @param commission the branch's commission when ordering
 * @param prepMinutes the store's prep estimate (placement default, then what the store said)
 * @param travelMinutes estimated travel time for delivery orders
 * @param acceptBy when a confirmed order is rejected automatically if the store has not answered
 * @param readyBy when the store said it would be ready
 * @param paymentUrl the card payment page while payment is pending
 * @param paidMinor amount paid by card
 * @param refundRequestedMinor amount asked to be given back
 * @param refundedMinor amount given back
 * @param cartVersion the cart version that was priced, to empty the cart after payment
 * @param timeline every step
 * @param closeReason why it was cancelled, rejected or failed
 * @param placedAt placed at
 * @param updatedAt last change
 * @param revision number of changes
 * @param deliveryCodeCipher the 4-digit code the customer gives the rider (PRD DR-6), encrypted with the PII key, or {@code null}
 * @param rider the rider on a delivery order, from dispatch, or {@code null}
 * @param handedBackAt when support gave an operator delivery back to the store, or {@code null}
 */
@Document("order_orders")
public record OrderDocument(@Id String id, String number, String customerId, String customerName, String contactPhone,
        Store store, OrderStatus status, Fulfilment fulfilment, DeliveryMode deliveryMode, Destination destination,
        PaymentMethod paymentMethod, String currency, String language, List<Line> lines, Totals totals, Coupon coupon,
        Commission commission, int prepMinutes, int travelMinutes, Instant acceptBy, Instant readyBy, String paymentUrl,
        long paidMinor, long refundRequestedMinor, long refundedMinor, long cartVersion, List<TimelineEntry> timeline,
        String closeReason, Instant placedAt, Instant updatedAt, long revision, String deliveryCodeCipher, Rider rider,
        Instant handedBackAt) {

    /**
     * The rider on the order, as dispatch reported it.
     *
     * @param riderId the rider's user id
     * @param name the rider's name, shown to the customer
     * @param vehicle how the rider travels
     * @param assignedAt when the rider took the job
     */
    public record Rider(String riderId, String name, String vehicle, Instant assignedAt) {
    }

    /**
     * @param storeId branch id
     * @param brandId brand id
     * @param name display name (brand and branch)
     * @param slug URL name
     * @param addressLine street address
     * @param city city
     * @param lat latitude
     * @param lng longitude
     * @param logoUrls logo variants
     * @param ownerUserIds people who manage the branch
     * @param timezone IANA time zone
     */
    public record Store(String storeId, String brandId, String name, String slug, String addressLine, String city, double lat,
            double lng, Map<String, String> logoUrls, Set<String> ownerUserIds, String timezone) {
    }

    /**
     * @param lat latitude
     * @param lng longitude
     * @param addressLine address as the customer wrote it
     * @param instructions directions for the rider, or {@code null}
     */
    public record Destination(double lat, double lng, String addressLine, String instructions) {
    }

    /**
     * @param itemId menu item
     * @param name item name by language
     * @param quantity how many
     * @param unitPriceMinor price of one, with options
     * @param options chosen options
     * @param lineTotalMinor quantity times unit price
     * @param note the customer's note for the kitchen, or {@code null}
     */
    public record Line(String itemId, Map<String, String> name, int quantity, long unitPriceMinor, List<Option> options,
            long lineTotalMinor, String note) {
    }

    /**
     * @param groupName option group by language
     * @param name option by language
     * @param priceDeltaMinor added to the item price
     */
    public record Option(Map<String, String> groupName, Map<String, String> name, long priceDeltaMinor) {
    }

    /**
     * @param subtotalMinor food before discounts
     * @param itemsDiscountMinor coupon discount on food
     * @param deliveryChargeMinor delivery charge
     * @param deliveryDiscountMinor coupon discount on delivery
     * @param serviceFeeMinor operator's service fee
     * @param taxMinor tax
     * @param tipMinor tip
     * @param totalMinor what the customer pays
     */
    public record Totals(long subtotalMinor, long itemsDiscountMinor, long deliveryChargeMinor, long deliveryDiscountMinor,
            long serviceFeeMinor, long taxMinor, long tipMinor, long totalMinor) {
    }

    /**
     * @param promotionId the promotion
     * @param code the code typed
     * @param title what the customer saw
     * @param operatorShareMinor part of the discount the operator pays
     */
    public record Coupon(String promotionId, String code, Map<String, String> title, long operatorShareMinor) {
    }

    /**
     * @param basisPoints percentage in hundredths of a percent
     * @param fixedMinor fixed amount per order
     */
    public record Commission(int basisPoints, long fixedMinor) {
    }

    /**
     * One step.
     *
     * @param status status after the step, or {@code null} for a note (such as a refund)
     * @param at when
     * @param actorRole {@code CUSTOMER}, {@code STORE}, {@code SUPPORT} or {@code SYSTEM}
     * @param actorId the user, or {@code null} for the system
     * @param note reason or detail, or {@code null}
     */
    public record TimelineEntry(OrderStatus status, Instant at, String actorRole, String actorId, String note) {
    }
}
