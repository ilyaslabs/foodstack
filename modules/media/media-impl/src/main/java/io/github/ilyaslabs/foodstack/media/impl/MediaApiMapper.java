package io.github.ilyaslabs.foodstack.media.impl;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaVariantView;
import io.github.ilyaslabs.foodstack.media.core.application.MediaAssetDetails;

/** Core results to API records. */
final class MediaApiMapper {

    private MediaApiMapper() {
    }

    static MediaAssetView toView(MediaAssetDetails details) {
        Map<String, MediaVariantView> variants = new LinkedHashMap<>();
        details.variants().forEach((name, variant) -> variants.put(name,
                new MediaVariantView(variant.url(), variant.width(), variant.height(), variant.bytes())));
        MediaOwner owner = (details.ownerModule() != null) ? new MediaOwner(details.ownerModule(), details.ownerEntityId())
                : null;
        return new MediaAssetView(details.id(), details.purpose(), details.status(), variants, owner,
                details.createdAt().getEpochSecond());
    }
}
