package io.github.ilyaslabs.foodstack.order.impl;

import io.github.ilyaslabs.foodstack.order.api.OrderApi;
import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/** {@link OrderApi} for other modules; the order service checks who may read the order. */
@Component
class OrderApiImpl implements OrderApi {

    private final OrderService orders;

    OrderApiImpl(OrderService orders) {
        this.orders = orders;
    }

    @Override
    public OrderSummary getSummary(String orderId) {
        ModuleAccess.requireAuthenticated();
        return this.orders.summary(orderId);
    }
}
