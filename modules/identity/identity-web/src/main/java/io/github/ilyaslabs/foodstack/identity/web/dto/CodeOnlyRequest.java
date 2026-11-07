package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A code from the authenticator app (or a recovery code), to confirm a change to two-step sign-in. */
public record CodeOnlyRequest(@NotBlank @Size(max = 20) String code) {

    @Override
    public String toString() {
        return "CodeOnlyRequest[]";
    }
}
