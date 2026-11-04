package io.github.ilyaslabs.foodstack.platform.core;

import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Fixed work buckets (TDD 7.5). Background work keyed by an aggregate (an outbox row by its
 * subject, a delivery job by its store, an order by its id) carries {@code bucket = hash(key) % 64},
 * and each replica works only on the buckets it holds a lease for, so adding replicas spreads the
 * work without any code or config change. The count never changes: a different count would move
 * keys between buckets and break per-key ordering.
 */
public final class Buckets {

    /** How many buckets there are. Fixed for the life of a deployment. */
    public static final int COUNT = 64;

    private Buckets() {
    }

    /** The bucket of a key, stable across replicas, restarts and versions. */
    public static int of(String key) {
        if (key == null) {
            return 0;
        }
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % COUNT);
    }
}
