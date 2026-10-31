package io.github.ilyaslabs.foodstack.media.api;

import io.github.ilyaslabs.moduleswitch.Param;

/**
 * What other modules may ask the media module. Errors: {@code MEDIA_NOT_FOUND} (404),
 * {@code MEDIA_NOT_READY} and {@code MEDIA_ALREADY_CLAIMED} (409), {@code MEDIA_PURPOSE_MISMATCH} (400).
 */
public interface MediaApi {

    /** An asset with the URLs of its variants. */
    MediaAssetView getAsset(@Param("mediaId") String mediaId);

    /**
     * Marks a ready asset as used by an entity of another module, so cleanup keeps it. The asset
     * must have been uploaded for {@code purpose}. Claiming again for the same owner is a no-op.
     */
    MediaAssetView claim(@Param("mediaId") String mediaId, @Param("purpose") MediaPurpose purpose,
            @Param("owner") MediaOwner owner);

    /** Releases a claim; the asset becomes eligible for cleanup. Unknown or unclaimed assets are ignored. */
    void release(@Param("mediaId") String mediaId, @Param("owner") MediaOwner owner);
}
