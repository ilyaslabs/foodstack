package io.github.ilyaslabs.foodstack.media.impl;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.media.api.MediaAssetView;
import io.github.ilyaslabs.foodstack.media.api.MediaOwner;
import io.github.ilyaslabs.foodstack.media.api.MediaPurpose;
import io.github.ilyaslabs.foodstack.media.core.application.MediaService;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;

import org.springframework.stereotype.Component;

/**
 * {@link MediaApi} for other modules: delegates to the core service and maps the result. Any caller,
 * signed in or not, may read an asset: images are shown to everyone (the branding logo on the login
 * page, menu photos). Only back-office users who manage what an image belongs to (branding,
 * stores, their own brand) or the platform may claim and release one (TDD 9.1).
 */
@Component
class MediaApiImpl implements MediaApi {

    /** Permissions of the screens that attach images: branding, any store, an owner's own store. */
    private static final String[] MANAGERS = { "settings:manage", "store:manage", "store:portal", "media:upload" };

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
        ModuleAccess.requireServiceOr(MANAGERS);
        return MediaApiMapper.toView(this.mediaService.claim(mediaId, purpose, owner.module(), owner.entityId()));
    }

    @Override
    public void release(String mediaId, MediaOwner owner) {
        ModuleAccess.requireServiceOr(MANAGERS);
        this.mediaService.release(mediaId, owner.module(), owner.entityId());
    }
}
