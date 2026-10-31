package io.github.ilyaslabs.foodstack.media.api;

public enum MediaStatus {

    /** Upload URL issued; waiting for the client to upload and complete. */
    PENDING_UPLOAD,

    /** Processed; variants are available. */
    READY,

    /** The upload was rejected (not an image, too large, corrupt). */
    FAILED
}
