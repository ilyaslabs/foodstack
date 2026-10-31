package io.github.ilyaslabs.foodstack.platform.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rejects access tokens whose {@code tv} claim is older than the user's revoked version. */
public class TokenVersionValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "The token has been revoked", null);

    private final TokenRevocations revocations;

    public TokenVersionValidator(TokenRevocations revocations) {
        this.revocations = revocations;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object version = token.getClaims().get(TokenClaims.TOKEN_VERSION);
        if (version == null) {
            // Client-credentials tokens of internal jobs carry no user and no version.
            return OAuth2TokenValidatorResult.success();
        }
        long tokenVersion = (version instanceof Number number) ? number.longValue() : Long.parseLong(version.toString());
        return this.revocations.isRevoked(token.getSubject(), tokenVersion) ? OAuth2TokenValidatorResult.failure(REVOKED)
                : OAuth2TokenValidatorResult.success();
    }
}
