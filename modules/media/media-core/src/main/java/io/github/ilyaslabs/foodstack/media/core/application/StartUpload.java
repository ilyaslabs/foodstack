package io.github.ilyaslabs.foodstack.media.core.application;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;

/**
 * A request to upload one image.
 *
 * @param purpose why it is uploaded
 * @param contentType {@code image/jpeg}, {@code image/png} or {@code image/webp}
 * @param sizeBytes exact size the client will upload
 */
public record StartUpload(MediaPurpose purpose, String contentType, long sizeBytes) {
}
