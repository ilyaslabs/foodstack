package io.github.ilyaslabs.foodstack.notification.core.push;

import java.util.Map;

/** Sends one push message to one device (TDD 8.3, FCM). */
public interface PushTransport {

    /**
     * @param token the device's registration token
     * @param title notification title
     * @param body notification text
     * @param link URL to open when tapped, or {@code null}
     * @param data small key/value payload for the app (no personal data)
     * @return what happened
     */
    Result send(String token, String title, String body, String link, Map<String, String> data);

    enum Result {
        SENT,
        /** The token is no longer valid; forget the device. */
        INVALID_TOKEN,
        /** Try again later. */
        FAILED
    }
}
