package io.github.ilyaslabs.foodstack.media.web.dto;

import java.util.Map;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.api.MediaStatus;

/**
 * An asset in the public API.
 *
 * @param id media id
 * @param purpose why it was uploaded
 * @param status processing state
 * @param variants sizes by name
 * @param createdAt UTC epoch seconds
 */
@EVL.Response(id = "media.asset-response", name = "Media Asset Response")
public record MediaAssetResponse(String id, MediaPurpose purpose, MediaStatus status, Map<String, Variant> variants,
        long createdAt) {

    public record Variant(String url, int width, int height, long bytes) {
    }
}
