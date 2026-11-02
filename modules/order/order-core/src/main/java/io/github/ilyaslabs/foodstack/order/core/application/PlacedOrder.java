package io.github.ilyaslabs.foodstack.order.core.application;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;

/**
 * A placed order.
 *
 * @param orderId order id
 * @param number order number
 * @param status {@code CONFIRMED} (cash) or {@code PAYMENT_PENDING} (card)
 * @param paymentUrl the card payment page to send the customer to, or {@code null}
 */
public record PlacedOrder(String orderId, String number, OrderStatus status, String paymentUrl) {
}
