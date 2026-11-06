package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A price change waiting for the operator's approval (PRD ST-6, BB-4) in {@code store_price_changes};
 * shard key {@code { brandId: hashed }}. There is at most one pending change per price: the id is
 * {@code <brandId>:<storeId or "brand">:<itemId>[:<optionId>]}, and a newer request replaces the
 * pending one. Decided changes stay as a record until a newer request for the same price.
 *
 * @param id see above
 * @param brandId the brand
 * @param storeId the branch for a branch price, or {@code null} for the brand menu price
 * @param itemId the menu item
 * @param optionId the modifier option, or {@code null} for the item's own price
 * @param currentMinor the price when asked (item price, or option price difference)
 * @param proposedMinor the asked price; {@code null} removes a branch's own price
 * @param status {@code PENDING}, {@code APPROVED} or {@code REJECTED}
 * @param requestedBy who asked
 * @param requestedAt when
 * @param decidedBy operator staff who decided, or {@code null}
 * @param decidedAt when decided, or {@code null}
 * @param version optimistic lock
 */
@Document("store_price_changes")
public record PriceChangeDocument(@Id String id, String brandId, String storeId, String itemId, String optionId,
        long currentMinor, Long proposedMinor, String status, String requestedBy, Instant requestedAt, String decidedBy,
        Instant decidedAt, @Version Long version) {

    public static final String PENDING = "PENDING";

    public static final String APPROVED = "APPROVED";

    public static final String REJECTED = "REJECTED";

    public static String idOf(String brandId, String storeId, String itemId, String optionId) {
        return brandId + ":" + ((storeId != null) ? storeId : "brand") + ":" + itemId
                + ((optionId != null) ? ":" + optionId : "");
    }
}
