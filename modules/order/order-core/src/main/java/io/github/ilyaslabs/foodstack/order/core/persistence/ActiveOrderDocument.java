package io.github.ilyaslabs.foodstack.order.core.persistence;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An order still in progress, in the small unsharded {@code order_active} collection (TDD 6.3): the
 * live store board and the accept-timeout sweep read it. Written in the same transaction as the
 * order and removed when the order ends.
 *
 * @param id order id
 * @param storeId branch
 * @param status current status
 * @param acceptBy accept deadline while confirmed, otherwise {@code null}
 * @param placedAt placed at
 */
@Document("order_active")
public record ActiveOrderDocument(@Id String id, String storeId, OrderStatus status, Instant acceptBy, Instant placedAt) {
}
