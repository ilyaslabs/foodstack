package io.github.ilyaslabs.foodstack.media.core.processing;

/** The upload is not an acceptable image. */
public class ImageRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public ImageRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return this.code;
    }
}
