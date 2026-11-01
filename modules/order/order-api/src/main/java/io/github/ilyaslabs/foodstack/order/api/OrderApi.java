package io.github.ilyaslabs.foodstack.order.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the order module. Most of them follow the order events instead;
 * this read is for the few that need an order's current state. Errors: {@code ORDER_NOT_FOUND} (404).
 */
public interface OrderApi {

    /** The order's summary as it is now. Its customer, its store's people, operator staff or the platform. */
    OrderSummary getSummary(@Param("orderId") String orderId);
}
