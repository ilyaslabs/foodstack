package io.github.ilyaslabs.foodstack.media.api;

import java.util.Map;

/**
 * An uploaded asset.
 *
 * @param id media id
 * @param purpose why it was uploaded
 * @param status processing state
 * @param variants processed sizes keyed by name ({@code thumb}, {@code card}, {@code detail}); empty until ready
 * @param owner who uses it, or {@code null}
 * @param createdAt UTC epoch seconds
 */
public record MediaAssetView(String id, MediaPurpose purpose, MediaStatus status, Map<String, MediaVariantView> variants,
        MediaOwner owner, long createdAt) {
}
