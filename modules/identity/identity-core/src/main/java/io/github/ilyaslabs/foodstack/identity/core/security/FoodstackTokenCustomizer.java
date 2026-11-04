package io.github.ilyaslabs.foodstack.identity.core.security;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import io.github.ilyaslabs.foodstack.platform.security.TokenClaims;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Signs service tokens from the client-credentials endpoint with ES256 and gives the service
 * client its one permission (TDD 9.1). User tokens come from {@link AccessTokens}.
 */
public class FoodstackTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        context.getJwsHeader().algorithm(SignatureAlgorithm.ES256);
        if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())
                && ModuleAccess.SERVICE_CLIENT.equals(context.getRegisteredClient().getClientId())) {
            context.getClaims().claim(TokenClaims.PERMISSIONS, List.of(ModuleAccess.SERVICE));
        }
    }
}
