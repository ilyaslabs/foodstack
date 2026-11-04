package io.github.ilyaslabs.foodstack.dispatch.core.persistence;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.platform.core.Buckets;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An open job in {@code dispatch_active} (id = order id), shard key {@code { _id: hashed }}: small,
 * indexed by status and rider, for the matching sweep, the riders' lists and support's board.
 * Removed when the job closes. {@code bucket} is the work bucket of the branch (TDD 7.5): each
 * replica matches only the jobs of the buckets it holds, so one branch's jobs stay on one replica.
 *
 * @param id order id
 * @param status job status
 * @param mode {@code OPERATOR} or {@code SELF}
 * @param storeId branch
 * @param riderId rider on the job, or {@code null}
 * @param offerRiderId rider with the open offer, or {@code null}
 * @param offerExpiresAt when the open offer lapses
 * @param searchFrom when offers may start
 * @param createdAt created at
 * @param bucket work bucket of the branch
 */
@Document("dispatch_active")
public record ActiveJobDocument(@Id String id, String status, String mode, String storeId, String riderId, String offerRiderId,
        Instant offerExpiresAt, Instant searchFrom, Instant createdAt, int bucket) {

    public ActiveJobDocument(String id, String status, String mode, String storeId, String riderId, String offerRiderId,
            Instant offerExpiresAt, Instant searchFrom, Instant createdAt) {
        this(id, status, mode, storeId, riderId, offerRiderId, offerExpiresAt, searchFrom, createdAt, Buckets.of(storeId));
    }
}
