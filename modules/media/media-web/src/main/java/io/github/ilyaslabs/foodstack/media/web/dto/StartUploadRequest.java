package io.github.ilyaslabs.foodstack.media.web.dto;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * {@code POST /api/v1/media/uploads}.
 *
 * @param purpose why the image is uploaded
 * @param contentType {@code image/jpeg}, {@code image/png} or {@code image/webp}
 * @param sizeBytes exact size of the file
 */
public record StartUploadRequest(@NotNull MediaPurpose purpose, @NotBlank String contentType, @Positive long sizeBytes) {
}
