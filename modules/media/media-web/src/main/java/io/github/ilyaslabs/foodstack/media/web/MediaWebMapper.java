package io.github.ilyaslabs.foodstack.media.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.core.application.MediaAssetDetails;
import io.github.ilyaslabs.foodstack.media.core.application.UploadTicket;
import io.github.ilyaslabs.foodstack.media.web.dto.MediaAssetResponse;
import io.github.ilyaslabs.foodstack.media.web.dto.UploadTicketResponse;

/** Core results to REST responses. */
final class MediaWebMapper {

    private MediaWebMapper() {
    }

    static MediaAssetResponse toResponse(MediaAssetDetails details) {
        Map<String, MediaAssetResponse.Variant> variants = new LinkedHashMap<>();
        details.variants().forEach((name, variant) -> variants.put(name,
                new MediaAssetResponse.Variant(variant.url(), variant.width(), variant.height(), variant.bytes())));
        return new MediaAssetResponse(details.id(), details.purpose(), details.status(), variants,
                details.createdAt().getEpochSecond());
    }

    static UploadTicketResponse toResponse(UploadTicket ticket) {
        return new UploadTicketResponse(toResponse(ticket.asset()), ticket.uploadUrl().toString(), "PUT", ticket.headers(),
                ticket.expiresAt().getEpochSecond());
    }
}
