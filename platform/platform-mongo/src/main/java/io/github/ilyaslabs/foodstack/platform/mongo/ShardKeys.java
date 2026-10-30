package io.github.ilyaslabs.foodstack.platform.mongo;

import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;

/**
 * The shard key of every sharded collection of a module (TDD 6.3). Collections not listed stay
 * unsharded. Keys are documented next to the collection in the module's changelog.
 */
public final class ShardKeys {

    private final Map<String, Document> keys = new LinkedHashMap<>();

    private ShardKeys() {
    }

    public static ShardKeys none() {
        return new ShardKeys();
    }

    public static ShardKeys builder() {
        return new ShardKeys();
    }

    /** {@code { field: "hashed" }}. */
    public ShardKeys hashed(String collection, String field) {
        this.keys.put(collection, new Document(field, "hashed"));
        return this;
    }

    /** Ranged key on one or more fields, ascending. */
    public ShardKeys ranged(String collection, String... fields) {
        Document key = new Document();
        for (String field : fields) {
            key.append(field, 1);
        }
        this.keys.put(collection, key);
        return this;
    }

    public Map<String, Document> asMap() {
        return Map.copyOf(this.keys);
    }
}
