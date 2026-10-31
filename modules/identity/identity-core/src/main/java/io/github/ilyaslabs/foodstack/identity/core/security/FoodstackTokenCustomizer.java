package io.github.ilyaslabs.foodstack.identity.core.security;

import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.platform.security.TokenClaims;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Signs every token with ES256 and puts {@code roles}, {@code perms}, {@code tv} and, for store
 * users, {@code brandIds} and {@code storeIds} into user
 * access tokens, so modules authorize without calling identity (TDD 9.1). Blocked users get no
 * new tokens, even with a live login session.
 */
public class FoodstackTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    private final UserService users;

    public FoodstackTokenCustomizer(UserService users) {
        this.users = users;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        context.getJwsHeader().algorithm(SignatureAlgorithm.ES256);
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                || context.getAuthorizationGrantType().getValue().equals("client_credentials")) {
            return;
        }
        UserService.TokenClaims claims;
        try {
            claims = this.users.tokenClaims(context.getPrincipal().getName());
        }
        catch (ModuleException ex) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED, ex.getMessage(), null));
        }
        context.getClaims()
            .claim(TokenClaims.ROLES, claims.roles().stream().map(Enum::name).sorted().toList())
            .claim(TokenClaims.PERMISSIONS, List.copyOf(claims.permissions()))
            .claim(TokenClaims.TOKEN_VERSION, claims.tokenVersion());
        if (!claims.brandIds().isEmpty()) {
            context.getClaims().claim(TokenClaims.BRAND_IDS, claims.brandIds().stream().sorted().toList());
        }
        if (!claims.storeIds().isEmpty()) {
            context.getClaims().claim(TokenClaims.STORE_IDS, claims.storeIds().stream().sorted().toList());
        }
    }
}
