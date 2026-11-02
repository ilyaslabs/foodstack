package io.github.ilyaslabs.foodstack.identity.api.events;

import java.time.Instant;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A one-time code was issued for the user to type on the login page (TDD 9.1). Topic
 * {@code foodstack.identity.events}, type {@code foodstack.identity.EmailCodeIssued.v1}. The
 * notification module emails it. The code travels encrypted with the platform PII key, and identity
 * keeps only its hash.
 *
 * @param userId who asked
 * @param purpose {@link #LOGIN} or {@link #PASSWORD_RESET}
 * @param codeCipher the code, encrypted with the PII key
 * @param expiresAt when it stops working
 */
public record EmailCodeIssued(String userId, String purpose, String codeCipher, Instant expiresAt) implements ModuleEvent {

    public static final String LOGIN = "LOGIN";

    public static final String PASSWORD_RESET = "PASSWORD_RESET";

    @Override
    public String subject() {
        return this.userId;
    }
}
