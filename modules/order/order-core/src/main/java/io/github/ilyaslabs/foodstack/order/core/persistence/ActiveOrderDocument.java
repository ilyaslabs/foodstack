package io.github.ilyaslabs.foodstack.order.core.persistence;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.platform.core.Buckets;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

/**
 * An order still in progress, in {@code order_active} (TDD 6.3), sharded on
 * {@code { storeId: hashed }} so the many writes of busy hours spread over every shard and the
 * store board reads one shard: the live board and the accept-timeout sweep read it. Written in
 * the same transaction as the order and removed when the order ends. The sweep reads only the
 * work buckets of its replica (TDD 7.5).
 *
 * @param id order id
 * @param storeId branch
 * @param status current status
 * @param acceptBy accept deadline while confirmed, otherwise {@code null}
 * @param placedAt placed at
 * @param bucket work bucket of the order id
 */
@Document("order_active")
@Sharded(shardKey = "storeId", shardingStrategy = ShardingStrategy.HASH, immutableKey = true)
public record ActiveOrderDocument(@Id String id, String storeId, OrderStatus status, Instant acceptBy, Instant placedAt,
        int bucket) {

    public ActiveOrderDocument(String id, String storeId, OrderStatus status, Instant acceptBy, Instant placedAt) {
        this(id, storeId, status, acceptBy, placedAt, Buckets.of(id));
    }
}
