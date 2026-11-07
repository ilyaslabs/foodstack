package io.github.ilyaslabs.foodstack.identity.web.dto;

/**
 * The answer to a first sign-in step when the account needs a second one (TDD 9.1): no tokens yet.
 *
 * @param next {@code TOTP} (enter a code) or {@code TOTP_ENROLL} (set up an authenticator app first)
 * @param challenge send back with the second step
 * @param expiresIn seconds the challenge stays valid
 */
public record SecondStepResponse(String next, String challenge, long expiresIn) {
}
