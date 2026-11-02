package io.github.ilyaslabs.foodstack.dispatch.api;

import java.util.Set;

/** Where a delivery job is. */
public enum DeliveryStatus {

    /** A self-delivery order with no rider chosen yet; the store may pick one or deliver itself. */
    WAITING,
    /** Looking for an operator rider; offers go out from shortly before the food is ready. */
    SEARCHING,
    /** An operator rider has an offer open. */
    OFFERED,
    /** A rider took the job and is on the way to the store. */
    ASSIGNED,
    /** The rider is at the store. */
    AT_STORE,
    /** The rider has the food. */
    PICKED_UP,
    DELIVERED,
    /** The order was cancelled or rejected. */
    CANCELLED,
    /** Support gave an operator delivery back to the store to deliver itself (PRD OF-8). */
    HANDED_BACK;

    private static final Set<DeliveryStatus> FINAL = Set.of(DELIVERED, CANCELLED, HANDED_BACK);

    public boolean isFinal() {
        return FINAL.contains(this);
    }
}
