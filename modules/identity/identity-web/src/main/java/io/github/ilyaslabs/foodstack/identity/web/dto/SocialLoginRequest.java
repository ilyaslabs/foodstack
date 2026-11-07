package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/social} (PRD ID-2): the ID token the app got from Google or Apple.
 *
 * @param client {@code web} or {@code mobile}
 * @param provider {@code google} or {@code apple}
 * @param idToken the provider's ID token (a JWT)
 * @param displayName optional; Apple sends the name only to the app, and only the first time
 */
public record SocialLoginRequest(@NotBlank String client, @NotBlank @Size(max = 20) String provider,
        @NotBlank @Size(max = 8192) String idToken, @Size(max = 80) String displayName) {

    @Override
    public String toString() {
        return "SocialLoginRequest[client=" + this.client + ", provider=" + this.provider + "]";
    }
}
