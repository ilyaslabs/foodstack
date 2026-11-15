package io.github.ilyaslabs.foodstack.store.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The web address of a branch in {@code store_slugs}; shard key {@code { _id: hashed }}. The slug
 * is the id, so it stays unique across shards (a unique index on {@code store_stores.slug} would
 * need the shard key), and a customer link is read through it. A changed slug frees the old one;
 * old links do not redirect (TDD 22).
 */
@Document("store_slugs")
public class StoreSlugDocument {

    @Id
    private String slug;

    private String storeId;

    private Instant createdAt;

    public StoreSlugDocument() {
    }

    public StoreSlugDocument(String slug, String storeId, Instant createdAt) {
        this.slug = slug;
        this.storeId = storeId;
        this.createdAt = createdAt;
    }

    public String getSlug() {
        return this.slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getStoreId() {
        return this.storeId;
    }

    public void setStoreId(String storeId) {
        this.storeId = storeId;
    }

    public Instant getCreatedAt() {
        return this.createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
