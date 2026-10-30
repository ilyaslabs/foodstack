package io.github.ilyaslabs.foodstack.media.web.dto;

import java.util.Map;

/**
 * Where to upload: {@code method uploadUrl} with {@code headers}, before {@code expiresAt}; then
 * {@code POST /api/v1/media/uploads/{id}/complete}.
 *
 * @param media the pending asset
 * @param uploadUrl presigned storage URL
 * @param method always {@code PUT}
 * @param headers headers the upload must send
 * @param expiresAt UTC epoch seconds
 */
public record UploadTicketResponse(MediaAssetResponse media, String uploadUrl, String method, Map<String, String> headers,
        long expiresAt) {
}
