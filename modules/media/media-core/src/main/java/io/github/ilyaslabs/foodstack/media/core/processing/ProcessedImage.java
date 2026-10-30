package io.github.ilyaslabs.foodstack.media.core.processing;

/**
 * One encoded variant.
 *
 * @param variant which size
 * @param width pixels
 * @param height pixels
 * @param webp the WebP bytes
 */
public record ProcessedImage(ImageVariant variant, int width, int height, byte[] webp) {
}
