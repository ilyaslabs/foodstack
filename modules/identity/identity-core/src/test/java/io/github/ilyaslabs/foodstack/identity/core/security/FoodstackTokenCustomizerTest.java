package io.github.ilyaslabs.foodstack.identity.core.security;

import io.github.ilyaslabs.foodstack.platform.security.ModuleAccess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import static org.assertj.core.api.Assertions.assertThat;

class FoodstackTokenCustomizerTest {

    private final FoodstackTokenCustomizer customizer = new FoodstackTokenCustomizer();

    @Test
    @DisplayName("signs service tokens with ES256 and gives the service client its permission")
    void serviceToken() {
        JwsHeader.Builder header = JwsHeader.with(SignatureAlgorithm.RS256);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject(ModuleAccess.SERVICE_CLIENT);

        this.customizer.customize(context(ModuleAccess.SERVICE_CLIENT, header, claims));

        assertThat(header.build().getAlgorithm()).isEqualTo(SignatureAlgorithm.ES256);
        assertThat(claims.build().getClaimAsStringList("perms")).containsExactly(ModuleAccess.SERVICE);
    }

    @Test
    @DisplayName("gives no permission to any other client")
    void otherClient() {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("other");

        this.customizer.customize(context("other", JwsHeader.with(SignatureAlgorithm.RS256), claims));

        assertThat(claims.build().getClaims()).doesNotContainKey("perms");
    }

    private static JwtEncodingContext context(String clientId, JwsHeader.Builder header, JwtClaimsSet.Builder claims) {
        RegisteredClient client = RegisteredClient.withId(clientId)
            .clientId(clientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .build();
        return JwtEncodingContext.with(header, claims)
            .tokenType(OAuth2TokenType.ACCESS_TOKEN)
            .registeredClient(client)
            .principal(new TestingAuthenticationToken(clientId, null))
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .build();
    }
}
