package io.github.ilyaslabs.foodstack.identity.web.dto;

/**
 * What an authenticator app needs (PRD ID-5): show {@code uri} as a QR code, or the secret to type in.
 *
 * @param secret base32 secret
 * @param uri {@code otpauth://} link
 */
public record EnrollmentResponse(String secret, String uri) {
}
