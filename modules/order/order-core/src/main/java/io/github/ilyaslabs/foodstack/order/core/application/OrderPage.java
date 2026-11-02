package io.github.ilyaslabs.foodstack.order.core.application;

import java.util.List;

/**
 * A page of orders, newest first.
 *
 * @param items orders
 * @param next cursor for the next page, or {@code null} at the end
 */
public record OrderPage(List<OrderRow> items, String next) {
}
