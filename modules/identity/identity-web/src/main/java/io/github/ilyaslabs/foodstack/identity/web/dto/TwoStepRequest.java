package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The second sign-in step (PRD ID-5): the challenge from the first step and, except when starting
 * to set up an app, a code from the authenticator app or a recovery code.
 */
public record TwoStepRequest(@NotBlank @Size(max = 2048) String challenge, @Size(max = 20) String code) {

    @Override
    public String toString() {
        return "TwoStepRequest[]";
    }
}
