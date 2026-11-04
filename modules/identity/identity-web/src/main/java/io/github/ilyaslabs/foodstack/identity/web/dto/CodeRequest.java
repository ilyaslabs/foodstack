package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/code}: email a 6-digit code to sign in or to set a new password.
 *
 * @param email login email; unknown emails get the same answer and no email
 * @param purpose {@code LOGIN} or {@code PASSWORD_RESET}
 */
public record CodeRequest(@NotBlank @Size(max = 254) String email, @NotBlank String purpose) {

    @Override
    public String toString() {
        return "CodeRequest[purpose=" + this.purpose + "]";
    }
}
