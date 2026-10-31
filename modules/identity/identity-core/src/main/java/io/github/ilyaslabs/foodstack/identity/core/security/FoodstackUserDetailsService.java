package io.github.ilyaslabs.foodstack.identity.core.security;

import io.github.ilyaslabs.foodstack.identity.api.UserStatus;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserDocument;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Looks users up by email for the login form. The authenticated name is the user id, so the
 * {@code sub} of every token is the stable id, never the email.
 */
public class FoodstackUserDetailsService implements UserDetailsService {

    private final UserService users;

    public FoodstackUserDetailsService(UserService users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        UserDocument user = this.users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(user.getId())
            .password(user.getPasswordHash())
            .roles(user.getRoles().stream().map(Enum::name).toArray(String[]::new))
            .disabled(user.getStatus() != UserStatus.ACTIVE)
            .build();
    }
}
