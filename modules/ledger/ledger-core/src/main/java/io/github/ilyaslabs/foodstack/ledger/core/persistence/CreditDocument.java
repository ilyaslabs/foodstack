package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A branch's direct-collection state in {@code ledger_credit} (shard key {@code { _id: hashed }}),
 * copied from the store module's {@code StorePaymentCollectionChanged}; {@code exceeded} changes
 * only through conditional updates, so each crossing of the limit is announced once.
 *
 * @param id the branch
 * @param brandId its brand
 * @param storeName the branch's name, for messages
 * @param ownerUserIds who is told when it goes over its limit
 * @param mode {@code OPERATOR} or {@code DIRECT}
 * @param creditLimitMinor the branch's own limit, or {@code null} for the operator's default
 * @param exceeded whether it is over the limit now
 * @param owedMinor what it owed at the last check
 * @param checkedAt the last check
 */
@Document("ledger_credit")
public record CreditDocument(@Id String id, String brandId, String storeName, java.util.Set<String> ownerUserIds, String mode, Long creditLimitMinor, boolean exceeded,
        long owedMinor, Instant checkedAt) {

    public static final String DIRECT = "DIRECT";
}
