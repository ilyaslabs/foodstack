package io.github.ilyaslabs.foodstack.order.api;

import java.util.Set;

/** Where an order is in its life (PRD 3). */
public enum OrderStatus {

    /** Saved; a cash order moves on to CONFIRMED at once. */
    PLACED,
    /** Waiting for the card payment on the gateway's page. */
    PAYMENT_PENDING,
    /** Paid (or cash) and waiting for the store to accept. */
    CONFIRMED,
    ACCEPTED,
    PREPARING,
    READY,
    /** On the way (delivery orders). */
    PICKED_UP,
    DELIVERED,
    /** Collected by the customer (pickup orders). */
    COLLECTED,
    /** The store said no, or did not answer in time. */
    REJECTED,
    CANCELLED,
    /** The card payment failed or was abandoned. */
    FAILED;

    private static final Set<OrderStatus> FINAL = Set.of(DELIVERED, COLLECTED, REJECTED, CANCELLED, FAILED);

    /** Whether nothing more happens to the order. */
    public boolean isFinal() {
        return FINAL.contains(this);
    }
}
