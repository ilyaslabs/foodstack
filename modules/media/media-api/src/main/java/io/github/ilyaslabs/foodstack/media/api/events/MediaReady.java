package io.github.ilyaslabs.foodstack.media.api.events;

import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * An upload was processed and its variants are available. Topic {@code foodstack.media.events},
 * type {@code foodstack.media.MediaReady.v1}.
 *
 * @param mediaId the asset
 * @param purpose why it was uploaded
 * @param variants names of the processed variants
 * @param uploadedBy user who uploaded it
 */
public record MediaReady(String mediaId, MediaPurpose purpose, java.util.List<String> variants, String uploadedBy)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.mediaId;
    }
}
