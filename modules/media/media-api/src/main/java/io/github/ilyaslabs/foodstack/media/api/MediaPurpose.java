package io.github.ilyaslabs.foodstack.media.api;

/** Why an asset was uploaded; decides its bucket and who may upload it. */
public enum MediaPurpose {

    /** Operator logo and other branding images; public. */
    BRANDING(true),

    /** Store cover and logo; public. */
    STORE(true),

    /** Menu item photos; public. */
    MENU_ITEM(true),

    /** Rider identity and vehicle documents; private, served only through short-lived signed URLs. */
    RIDER_DOCUMENT(false);

    private final boolean publicAsset;

    MediaPurpose(boolean publicAsset) {
        this.publicAsset = publicAsset;
    }

    public boolean isPublic() {
        return this.publicAsset;
    }
}
