package io.github.ilyaslabs.foodstack.media.core.application;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

/**
 * Where and how the client uploads the bytes: {@code PUT uploadUrl} with {@code headers}.
 *
 * @param asset the pending asset
 * @param uploadUrl presigned URL
 * @param headers headers the upload must carry
 * @param expiresAt when the URL stops working
 */
public record UploadTicket(MediaAssetDetails asset, URI uploadUrl, Map<String, String> headers, Instant expiresAt) {
}
