package io.github.ilyaslabs.foodstack.media.core.application;

import io.github.ilyaslabs.moduleswitch.ModuleException;

/** Stable error codes of the media module. */
public final class MediaErrors {

    private MediaErrors() {
    }

    public static ModuleException.NotFound notFound(String mediaId) {
        return new ModuleException.NotFound("Media " + mediaId + " not found", "MEDIA_NOT_FOUND");
    }

    public static ModuleException.Conflict notReady(String mediaId) {
        return new ModuleException.Conflict("Media " + mediaId + " is not ready", "MEDIA_NOT_READY");
    }
}
