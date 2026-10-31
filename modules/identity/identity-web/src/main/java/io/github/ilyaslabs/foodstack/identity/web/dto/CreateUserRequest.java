package io.github.ilyaslabs.foodstack.identity.web.dto;

import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /api/v1/users}: an administrator creates a back-office account.
 *
 * @param email login email
 * @param password initial password, 10 to 128 characters
 * @param displayName name
 * @param roles back-office roles
 */
public record CreateUserRequest(@NotBlank @Email String email, @NotBlank @Size(min = 10, max = 128) String password,
        @NotBlank @Size(max = 80) String displayName, @NotEmpty Set<Role> roles) {

    @Override
    public String toString() {
        return "CreateUserRequest[displayName=" + this.displayName + ", roles=" + this.roles + "]";
    }
}
