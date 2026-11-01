package io.github.ilyaslabs.foodstack.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/users/{id}/block}.
 *
 * @param reason why, kept with the account (PRD ID-4)
 */
public record BlockUserRequest(@NotBlank @Size(max = 500) String reason) {
}
