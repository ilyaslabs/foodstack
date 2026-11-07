package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * One account's total for one kind on one day, from one work bucket, in {@code ledger_daily} (shard
 * key {@code { accountId: hashed }}). Rebuilt from the transactions, never added to, so building it
 * twice gives the same row. Reports read these instead of the transactions (TDD 6.4).
 *
 * @param id {@code accountId|day|kind|bucket}
 * @param accountId the account
 * @param partyType {@code STORE}, {@code RIDER}, {@code OPERATOR} or {@code CLEARING}
 * @param brandId the brand, for branch accounts
 * @param day {@code yyyy-MM-dd}
 * @param kind what the lines were for
 * @param bucket work bucket the transactions came from
 * @param amountMinor signed total
 * @param lines how many lines
 * @param currency ISO 4217 code
 */
@Document("ledger_daily")
@Sharded(shardKey = "accountId", immutableKey = true)
public record DailyDocument(@Id String id, String accountId, String partyType, String brandId, String day, String kind,
        int bucket, long amountMinor, long lines, String currency) {
}
