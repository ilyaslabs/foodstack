package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * A settled balance in {@code ledger_settlements} (shard key {@code { accountId: hashed }}; PRD
 * FI-3). Its ledger transaction has the same id.
 *
 * @param id settlement id
 * @param accountId the branch's or rider's account
 * @param partyType {@code STORE} or {@code RIDER}
 * @param partyId the branch or rider
 * @param brandId the branch's brand, or {@code null}
 * @param periodTo the last day settled, {@code yyyy-MM-dd}
 * @param amountMinor what the operator paid, negative when the party paid the operator
 * @param currency ISO 4217 code
 * @param reference the bank or payment reference
 * @param settledBy who settled it
 * @param settledAt when
 */
@Document("ledger_settlements")
@Sharded(shardKey = "accountId", immutableKey = true)
public record SettlementDocument(@Id String id, String accountId, String partyType, String partyId, String brandId,
        String periodTo, long amountMinor, String currency, String reference, String settledBy, Instant settledAt) {
}
