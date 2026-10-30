package io.github.ilyaslabs.foodstack.platform.security;

import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The user behind the current request, from the validated token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The {@code sub} of the current token; 401 when there is none. */
    public static String id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            return jwt.getToken().getSubject();
        }
        throw new ModuleException.Unauthorized("Authentication required", "UNAUTHENTICATED");
    }
}
