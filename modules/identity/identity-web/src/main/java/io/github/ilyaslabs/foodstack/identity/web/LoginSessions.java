package io.github.ilyaslabs.foodstack.identity.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;

/**
 * Signs a user in from the hosted pages (after sign-up, an email code or a password reset) as the
 * login form would, then continues the authorization request that sent them to the login page.
 */
class LoginSessions {

    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();

    private final HttpSessionSecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    private final UserDetailsService userDetails;

    LoginSessions(UserDetailsService userDetails) {
        this.userDetails = userDetails;
    }

    SavedRequest savedRequest(HttpServletRequest request, HttpServletResponse response) {
        return this.requestCache.getRequest(request, response);
    }

    /**
     * @param factor how the user proved who they are ({@link FactorGrantedAuthority#PASSWORD_AUTHORITY}
     * or {@link FactorGrantedAuthority#OTT_AUTHORITY}); it carries the sign-in time for the ID
     * token's {@code auth_time}
     * @param fallback where to go when no authorization request is waiting
     */
    ResponseEntity<String> signInAndContinue(String email, String factor, String fallback, HttpServletRequest request,
            HttpServletResponse response) {
        SavedRequest saved = savedRequest(request, response);
        UserDetails user = this.userDetails.loadUserByUsername(email.trim());
        // a new session id at sign-in, against session fixation
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        List<GrantedAuthority> authorities = new ArrayList<>(user.getAuthorities());
        authorities.add(FactorGrantedAuthority.withAuthority(factor).issuedAt(Instant.now()).build());
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, authorities));
        SecurityContextHolder.setContext(context);
        this.contextRepository.saveContext(context, request, response);
        String next = (saved != null) ? saved.getRedirectUrl() : fallback;
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header("Location", next).build();
    }
}
