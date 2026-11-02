package io.github.ilyaslabs.foodstack.order.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An {@code Idempotency-Key} already used to place an order (PRD CO-7), in
 * {@code order_placement_keys} ({@code _id = customerId:key}, shard key {@code { _id: hashed }}),
 * kept for a day.
 *
 * @param id customer id and key
 * @param orderId the order it placed
 * @param fingerprint a hash of the request, so a reused key with another request is refused
 * @param createdAt used at
 */
@Document("order_placement_keys")
public record PlacementKeyDocument(@Id String id, String orderId, String fingerprint, Instant createdAt) {
}
