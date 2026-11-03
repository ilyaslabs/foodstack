package io.github.ilyaslabs.foodstack.dispatch.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An open job in {@code dispatch_active} (id = order id), shard key {@code { _id: hashed }}: small,
 * indexed by status and rider, for the matching sweep, the riders' lists and support's board.
 * Removed when the job closes.
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
 */
@Document("dispatch_active")
public record ActiveJobDocument(@Id String id, String status, String mode, String storeId, String riderId, String offerRiderId,
        Instant offerExpiresAt, Instant searchFrom, Instant createdAt) {
}
