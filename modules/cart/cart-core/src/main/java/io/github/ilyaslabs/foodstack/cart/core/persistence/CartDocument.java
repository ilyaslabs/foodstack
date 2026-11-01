package io.github.ilyaslabs.foodstack.cart.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A customer's cart in {@code cart_carts}: one per customer, so the customer id is the {@code _id}
 * (shard key {@code { _id: hashed }}). Lines from one branch only (PRD CO-1). Expires 30 days
 * after the last change.
 *
 * @param id the customer id
 * @param storeId the branch, or {@code null} while empty
 * @param lines lines in the order they were added
 * @param updatedAt last change, drives the expiry index
 * @param version optimistic lock, also the cart version an order was priced at
 */
@Document("cart_carts")
public record CartDocument(@Id String id, String storeId, List<CartLine> lines, Instant updatedAt, @Version Long version) {

    /**
     * @param lineId stable id of the line
     * @param itemId menu item
     * @param quantity how many
     * @param optionIds chosen options
     * @param note note for the kitchen, or {@code null}
     */
    public record CartLine(String lineId, String itemId, int quantity, List<String> optionIds, String note) {
    }

    public long versionOrZero() {
        return (this.version != null) ? this.version : 0L;
    }
}
