package io.github.ilyaslabs.foodstack.order.api;

import java.util.Set;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

/**
 * What every order event carries, so consumers (projections, notifications, promotion, payment)
 * never need to call back. Amounts are minor units of {@code currency}; times are epoch seconds.
 *
 * @param orderId the order
 * @param number short order number shown to people, such as {@code 7K3Q-9XAB}
 * @param customerId who ordered
 * @param storeId the branch
 * @param storeName brand and branch name as shown to the customer
 * @param storeOwnerIds users who run the branch
 * @param status status after the event
 * @param fulfilment delivery or pickup
 * @param paymentMethod cash or card
 * @param itemCount items, quantities added up
 * @param totalMinor what the customer pays
 * @param currency ISO 4217 code
 * @param promotionId promotion of the coupon used, or {@code null}
 * @param language the customer's language when ordering, for messages
 * @param placedAt when the order was placed
 * @param money the order's money split into its parts, for the ledger; {@code null} on events sent before it was kept
 */
public record OrderSummary(String orderId, String number, String customerId, String storeId, String storeName,
        Set<String> storeOwnerIds, OrderStatus status, Fulfilment fulfilment, PaymentMethod paymentMethod, int itemCount,
        long totalMinor, String currency, String promotionId, String language, long placedAt, OrderMoney money) {

    public OrderSummary(String orderId, String number, String customerId, String storeId, String storeName,
            Set<String> storeOwnerIds, OrderStatus status, Fulfilment fulfilment, PaymentMethod paymentMethod, int itemCount,
            long totalMinor, String currency, String promotionId, String language, long placedAt) {
        this(orderId, number, customerId, storeId, storeName, storeOwnerIds, status, fulfilment, paymentMethod, itemCount,
                totalMinor, currency, promotionId, language, placedAt, null);
    }
}
