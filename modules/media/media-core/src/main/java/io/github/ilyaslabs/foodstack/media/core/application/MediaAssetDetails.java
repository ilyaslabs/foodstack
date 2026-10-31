package io.github.ilyaslabs.foodstack.media.core.application;

import java.time.Instant;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;

/**
 * An asset as the application services return it, with resolved variant URLs.
 *
 * @param id media id
 * @param purpose why it was uploaded
 * @param status processing state
 * @param variants by name
 * @param ownerModule owning module, or {@code null}
 * @param ownerEntityId owning entity, or {@code null}
 * @param createdAt when the upload started
 */
public record MediaAssetDetails(String id, MediaPurpose purpose, MediaStatus status, Map<String, Variant> variants,
        String ownerModule, String ownerEntityId, Instant createdAt) {

    public record Variant(String url, int width, int height, long bytes) {
    }
}
