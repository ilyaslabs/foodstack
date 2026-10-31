package io.github.ilyaslabs.foodstack.media.api;

/**
 * One processed size of an image.
 *
 * @param url public URL, or a short-lived signed URL for private assets
 * @param width pixels
 * @param height pixels
 * @param bytes size of the WebP file
 */
public record MediaVariantView(String url, int width, int height, long bytes) {
}
