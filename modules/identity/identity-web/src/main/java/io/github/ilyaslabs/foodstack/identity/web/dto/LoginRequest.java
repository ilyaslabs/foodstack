package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/login}: sign in with a password or an emailed code.
 *
 * @param client {@code web} (refresh token in an httpOnly cookie) or {@code mobile} (in the body)
 * @param email login email
 * @param password the password, or empty when signing in with a code
 * @param code the 6-digit code from {@code POST /api/v1/auth/code}, or empty with a password
 */
public record LoginRequest(@NotBlank String client, @NotBlank @Size(max = 254) String email,
        @Size(max = 128) String password, @Size(max = 12) String code) {

    @Override
    public String toString() {
        return "LoginRequest[client=" + this.client + "]";
    }
}
