package io.github.ilyaslabs.foodstack.ledger.core.persistence;

import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What the ledger keeps about an order in {@code ledger_orders} (shard key {@code { _id: hashed }}):
 * its branch, for postings that arrive without one, and refunds asked before the order was done,
 * which are posted when it is.
 *
 * @param id the order
 * @param storeId the branch
 * @param brandId the brand, or {@code null}
 * @param done whether the order was delivered or collected
 * @param closed whether it was cancelled, rejected or failed
 * @param collectedBy {@code OPERATOR} or {@code STORE}
 * @param pendingRefunds refunds asked while the order was in progress
 */
@Document("ledger_orders")
public record OrderDocument(@Id String id, String storeId, String brandId, Boolean done, Boolean closed, String collectedBy,
        List<PendingRefund> pendingRefunds) {

    /**
     * @param id the refund event
     * @param amountMinor how much
     * @param fundedBy {@code STORE} or {@code OPERATOR}
     * @param reason why
     */
    public record PendingRefund(String id, long amountMinor, String fundedBy, String reason) {
    }
}
