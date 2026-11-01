package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A branch's change to one brand menu item or modifier option in {@code store_item_overrides};
 * shard key {@code { storeId: hashed }} (read together when the branch menu is built). Id
 * {@code <storeId>:<itemId>[:<optionId>]}. Price overrides arrive with the multi-branch screens (M4).
 */
@Document("store_item_overrides")
public class ItemOverrideDocument {

    @Id
    private String id;

    private String storeId;

    private String itemId;

    private String optionId;

    private boolean soldOut;

    private Long priceOverrideMinor;

    private Instant updatedAt;

    private String updatedBy;

    @Version
    private Long version;


    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStoreId() {
        return this.storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public String getItemId() {
        return this.itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getOptionId() {
        return this.optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public boolean isSoldOut() {
        return this.soldOut;
    }

    public void setSoldOut(boolean soldOut) {
        this.soldOut = soldOut;
    }

    public Long getPriceOverrideMinor() {
        return this.priceOverrideMinor;
    }

    public void setPriceOverrideMinor(Long priceOverrideMinor) {
        this.priceOverrideMinor = priceOverrideMinor;
    }

    public Instant getUpdatedAt() {
        return this.updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return this.updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Long getVersion() {
        return this.version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
