package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tokens of a sign-in that just set up an authenticator app, with the recovery codes (shown once).
 * Without tokens when the app was set up from the account page.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecoveryCodesResponse(String accessToken, String tokenType, Long expiresIn, String refreshToken,
        Long refreshExpiresAt, List<String> recoveryCodes) {
}
