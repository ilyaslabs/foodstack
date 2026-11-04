package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;
import org.springframework.data.mongodb.core.mapping.ShardingStrategy;

/**
 * One refresh token of a sign-in (TDD 9.1). Only the SHA-256 of the token is kept, as the id.
 * Every rotation adds a token to the same family; a token presented after it was used means it
 * leaked, and the whole family is removed. Expired tokens are removed by a TTL index.
 *
 * @param id SHA-256 of the token, hex
 * @param familyId the sign-in this token belongs to; access tokens carry it as {@code sid}
 * @param userId the account
 * @param client {@code WEB} (cookie) or {@code MOBILE} (body); the token works only in its channel
 * @param tokenVersion the account's token version at sign-in; a newer one ends the sign-in
 * @param issuedAt when this token was issued
 * @param expiresAt when it stops working (30 days after it was issued)
 * @param usedAt when it was swapped for the next one, or {@code null}
 */
@Document("identity_refresh_tokens")
@Sharded(shardKey = "_id", shardingStrategy = ShardingStrategy.HASH, immutableKey = true)
public record RefreshTokenDocument(@Id String id, String familyId, String userId, String client, long tokenVersion,
        Instant issuedAt, Instant expiresAt, Instant usedAt) {
}
