package io.github.ilyaslabs.foodstack.media.impl;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.core.application.MediaService;

import org.springframework.stereotype.Component;

/** {@link MediaApi} for other modules: delegates to the core service and maps the result. */
@Component
class MediaApiImpl implements MediaApi {

    private final MediaService mediaService;

    MediaApiImpl(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Override
    public MediaAssetView getAsset(String mediaId) {
        return MediaApiMapper.toView(this.mediaService.get(mediaId));
    }

    @Override
    public MediaAssetView claim(String mediaId, MediaPurpose purpose, MediaOwner owner) {
        return MediaApiMapper.toView(this.mediaService.claim(mediaId, purpose, owner.module(), owner.entityId()));
    }

    @Override
    public void release(String mediaId, MediaOwner owner) {
        this.mediaService.release(mediaId, owner.module(), owner.entityId());
    }
}
