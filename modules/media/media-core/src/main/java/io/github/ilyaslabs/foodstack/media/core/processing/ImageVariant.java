package io.github.ilyaslabs.foodstack.media.core.processing;

/** The fixed sizes every image is resized to (TDD 13): longest side in pixels. */
public enum ImageVariant {

    THUMB("thumb", 160),

    CARD("card", 480),

    DETAIL("detail", 1080);

    private final String key;

    private final int maxSide;

    ImageVariant(String key, int maxSide) {
        this.key = key;
        this.maxSide = maxSide;
    }

    public String key() {
        return this.key;
    }

    public int maxSide() {
        return this.maxSide;
    }
}
