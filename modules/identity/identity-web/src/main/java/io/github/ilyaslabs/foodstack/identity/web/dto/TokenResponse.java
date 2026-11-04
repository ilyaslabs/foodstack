package io.github.ilyaslabs.foodstack.identity.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tokens of a sign-in. Keep the access token in memory only.
 *
 * @param accessToken bearer token for the API
 * @param tokenType always {@code Bearer}
 * @param expiresIn seconds until the access token runs out
 * @param refreshToken mobile only; browsers get it as an httpOnly cookie instead
 * @param refreshExpiresAt when the refresh token runs out, epoch seconds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
        long refreshExpiresAt) {
}
