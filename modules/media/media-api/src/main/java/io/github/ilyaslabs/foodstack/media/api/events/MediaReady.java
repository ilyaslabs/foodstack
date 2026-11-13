package io.github.ilyaslabs.foodstack.media.api.events;

import io.github.ilyaslabs.evl.EVL;
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
@EVL.Event(id = "media.media-ready", name = "Media Ready", channel = "<namespace>.media.events",
        delivery = EVL.Delivery.AT_LEAST_ONCE, ordering = "Per media id (Kafka key = media id)",
        schema = "foodstack.media.MediaReady.v1", broker = "platform.kafka")
public record MediaReady(String mediaId, MediaPurpose purpose, java.util.List<String> variants, String uploadedBy)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.mediaId;
    }
}
