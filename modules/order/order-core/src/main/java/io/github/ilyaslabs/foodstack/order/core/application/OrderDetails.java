package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * An order as its customer, its store and support staff see it. Times are epoch seconds.
 *
 * @param id order id
 * @param number order number
 * @param status current status
 * @param fulfilment delivery or pickup
 * @param deliveryMode who delivers, for delivery orders
 * @param paymentMethod cash or card
 * @param currency ISO 4217 code
 * @param customerId customer
 * @param customerName customer's name
 * @param contactPhone phone given at checkout, or {@code null}
 * @param store the branch
 * @param destination where to deliver, or {@code null}
 * @param lines items
 * @param totals money
 * @param coupon coupon used, or {@code null}
 * @param prepMinutes prep estimate
 * @param acceptBy accept deadline while waiting for the store, or {@code null}
 * @param readyBy when the store expects it ready, or {@code null}
 * @param estimatedAt when the customer can expect it (ready for pickup, or delivered), or {@code null}
 * @param paymentUrl card payment page while payment is pending, or {@code null}
 * @param paidMinor paid by card
 * @param refundRequestedMinor asked to be given back
 * @param refundedMinor given back
 * @param timeline steps, oldest first
 * @param closeReason why it was cancelled, rejected or failed
 * @param placedAt placed at
 * @param revision number of changes
 * @param actions what the viewer may do now
 * @param rider the rider on the way, or {@code null}
 * @param deliveryCode the code to give the rider at the door; shown to the customer only, else {@code null}
 * @param handedBackAt when support gave an operator delivery back to the store, or {@code null}
 */
public record OrderDetails(String id, String number, OrderStatus status, Fulfilment fulfilment, DeliveryMode deliveryMode,
        PaymentMethod paymentMethod, String currency, String customerId, String customerName, String contactPhone,
        StoreInfo store, Destination destination, List<Line> lines, Totals totals, Coupon coupon, int prepMinutes, Long acceptBy,
        Long readyBy, Long estimatedAt, String paymentUrl, long paidMinor, long refundRequestedMinor, long refundedMinor,
        List<Step> timeline, String closeReason, long placedAt, long revision, Set<String> actions, RiderInfo rider,
        String deliveryCode, Long handedBackAt) {

    /**
     * @param riderId the rider
     * @param name the rider's first name
     * @param vehicle bicycle, motorbike, scooter or car
     * @param assignedAt epoch seconds
     */
    public record RiderInfo(String riderId, String name, String vehicle, long assignedAt) {
    }

    /**
     * @param storeId branch id
     * @param name display name
     * @param slug URL name
     * @param addressLine address
     * @param city city
     * @param lat latitude
     * @param lng longitude
     * @param logoUrls logo variants
     */
    public record StoreInfo(String storeId, String name, String slug, String addressLine, String city, double lat, double lng,
            Map<String, String> logoUrls) {
    }

    /**
     * @param lat latitude
     * @param lng longitude
     * @param addressLine address
     * @param instructions directions, or {@code null}
     */
    public record Destination(double lat, double lng, String addressLine, String instructions) {
    }

    /**
     * @param itemId item
     * @param name name by language
     * @param quantity how many
     * @param unitPriceMinor price of one
     * @param options chosen options
     * @param lineTotalMinor line total
     * @param note kitchen note, or {@code null}
     */
    public record Line(String itemId, Map<String, String> name, int quantity, long unitPriceMinor, List<Option> options,
            long lineTotalMinor, String note) {
    }

    /**
     * @param groupName group by language
     * @param name option by language
     * @param priceDeltaMinor added price
     */
    public record Option(Map<String, String> groupName, Map<String, String> name, long priceDeltaMinor) {
    }

    /**
     * @param subtotalMinor food
     * @param itemsDiscountMinor discount on food
     * @param deliveryChargeMinor delivery
     * @param deliveryDiscountMinor discount on delivery
     * @param serviceFeeMinor service fee
     * @param taxMinor tax
     * @param tipMinor tip
     * @param totalMinor total
     */
    public record Totals(long subtotalMinor, long itemsDiscountMinor, long deliveryChargeMinor, long deliveryDiscountMinor,
            long serviceFeeMinor, long taxMinor, long tipMinor, long totalMinor) {
    }

    /**
     * @param code code
     * @param title title
     */
    public record Coupon(String code, Map<String, String> title) {
    }

    /**
     * @param status status after the step, or {@code null} for a note
     * @param at epoch seconds
     * @param actorRole who
     * @param note detail, or {@code null}
     */
    public record Step(OrderStatus status, long at, String actorRole, String note) {
    }
}
