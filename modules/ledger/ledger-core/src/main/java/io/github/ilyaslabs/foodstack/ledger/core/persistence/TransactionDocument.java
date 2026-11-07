package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.ledger.core.domain.Leg;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * One balanced ledger transaction in {@code ledger_transactions}, append only (TDD 6.3: shard key
 * {@code { partition: hashed }}, where {@code partition} is the branch, so a branch's statement reads
 * one shard; transactions with no branch use the order or the account). The id comes from the
 * event that caused it, so posting the same event twice writes nothing. {@code rolled} turns true once
 * the daily roll-ups include it.
 *
 * @param id what caused it, such as {@code order:<id>:done}
 * @param partition shard key: the branch, else the order, else the account
 * @param bucket work bucket of {@code partition}, for the roll-up workers
 * @param type what happened, such as {@code ORDER_DONE}
 * @param storeId the branch, or {@code null}
 * @param brandId the branch's brand, or {@code null}
 * @param orderId the order, or {@code null}
 * @param legs the lines, adding up to zero
 * @param currency ISO 4217 code
 * @param day the deployment's calendar day it counts for, {@code yyyy-MM-dd}
 * @param at when it happened
 * @param note detail such as a refund reason or a settlement reference, or {@code null}
 * @param rolled whether the daily roll-ups include it
 */
@Document("ledger_transactions")
@Sharded(shardKey = "partition", immutableKey = true)
public record TransactionDocument(@Id String id, String partition, int bucket, String type, String storeId, String brandId,
        String orderId, List<Leg> legs, String currency, String day, Instant at, String note, boolean rolled) {
}
