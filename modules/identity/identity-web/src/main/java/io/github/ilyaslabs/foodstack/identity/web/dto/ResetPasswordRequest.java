package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/reset}: set a new password with an emailed reset code, and sign in.
 *
 * @param client {@code web} or {@code mobile}
 * @param email login email
 * @param code the 6-digit reset code
 * @param newPassword 10 to 128 characters
 */
public record ResetPasswordRequest(@NotBlank String client, @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 12) String code, @NotBlank @Size(min = 10, max = 128) String newPassword) {

    @Override
    public String toString() {
        return "ResetPasswordRequest[client=" + this.client + "]";
    }
}
