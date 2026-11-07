package io.github.ilyaslabs.foodstack.payment.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Payment's copy of who collects for a branch, in {@code payment_store_collection} (shard key
 * {@code { _id: hashed }}): the mode from the store module and the suspension from the ledger's
 * credit limit (PRD DP-2, DP-7). A branch without a document is collected for by the operator.
 *
 * @param id the branch
 * @param brandId its brand, whose account it may use
 * @param mode {@code OPERATOR} or {@code DIRECT}
 * @param suspended over its credit limit: the operator collects until it is back within it
 * @param updatedAt last change
 */
@Document("payment_store_collection")
public record StoreCollectionDocument(@Id String id, String brandId, String mode, boolean suspended, Instant updatedAt) {

    public static final String DIRECT = "DIRECT";
}
