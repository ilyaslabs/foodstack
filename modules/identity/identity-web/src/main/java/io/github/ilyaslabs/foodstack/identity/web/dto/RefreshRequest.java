package io.github.ilyaslabs.foodstack.identity.web.dto;

/**
 * {@code POST /api/v1/auth/refresh} body. Mobile apps send the refresh token here; browsers send
 * an empty object and the cookie carries it. Sending both is refused.
 *
 * @param refreshToken the mobile app's refresh token, or {@code null}
 */
public record RefreshRequest(String refreshToken) {

    @Override
    public String toString() {
        return "RefreshRequest[]";
    }
}
