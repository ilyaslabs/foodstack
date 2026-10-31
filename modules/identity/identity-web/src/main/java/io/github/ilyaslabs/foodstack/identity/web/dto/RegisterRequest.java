package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/auth/register}: a customer signs up with email and password.
 *
 * @param email login email
 * @param password 10 to 128 characters
 * @param displayName name shown to stores and riders
 */
public record RegisterRequest(@NotBlank @Email String email, @NotBlank @Size(min = 10, max = 128) String password,
        @NotBlank @Size(max = 80) String displayName) {

    @Override
    public String toString() {
        return "RegisterRequest[displayName=" + this.displayName + "]";
    }
}
