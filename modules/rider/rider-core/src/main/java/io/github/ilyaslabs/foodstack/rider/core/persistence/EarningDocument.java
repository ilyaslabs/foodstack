package io.github.ilyaslabs.foodstack.rider.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * What a rider earned on one delivery, in {@code rider_earnings} (id = order id), from dispatch's
 * {@code DeliveryCompleted}. Shard key {@code { riderId: hashed }}: a rider's earnings are read together.
 *
 * @param id order id
 * @param riderId the rider
 * @param orderNumber order number
 * @param storeName store
 * @param earningMinor delivery pay (0 for a store's own rider, whom the store pays)
 * @param tipMinor tip, all of it the rider's
 * @param cashCollectedMinor cash taken at the door, owed to the operator or the store
 * @param currency ISO 4217 code
 * @param distanceMeters store to customer
 * @param at delivered at
 */
@Document("rider_earnings")
@Sharded(shardKey = "riderId", immutableKey = true)
public record EarningDocument(@Id String id, String riderId, String orderNumber, String storeName, long earningMinor,
        long tipMinor, long cashCollectedMinor, String currency, long distanceMeters, Instant at) {
}
