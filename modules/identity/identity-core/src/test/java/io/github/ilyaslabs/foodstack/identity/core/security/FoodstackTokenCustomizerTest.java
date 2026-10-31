package io.github.ilyaslabs.foodstack.identity.core.security;

import java.util.List;
import java.util.Set;

import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class FoodstackTokenCustomizerTest {

    private final UserService users = mock(UserService.class);

    private final FoodstackTokenCustomizer customizer = new FoodstackTokenCustomizer(this.users);

    @Test
    @DisplayName("signs with ES256 and adds roles, permissions and token version to access tokens")
    void accessToken() {
        given(this.users.tokenClaims("user-1"))
            .willReturn(new UserService.TokenClaims(Set.of(Role.ADMIN), Set.of("media:upload", "settings:manage"), 3));
        JwsHeader.Builder header = JwsHeader.with(SignatureAlgorithm.RS256);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("user-1");

        this.customizer.customize(context(OAuth2TokenType.ACCESS_TOKEN, header, claims));

        assertThat(header.build().getAlgorithm()).isEqualTo(SignatureAlgorithm.ES256);
        JwtClaimsSet built = claims.build();
        assertThat(built.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(built.getClaimAsStringList("perms")).containsExactlyInAnyOrder("media:upload", "settings:manage");
        assertThat(built.<Long>getClaim("tv")).isEqualTo(3L);
    }

    @Test
    @DisplayName("refuses new tokens for blocked users")
    void blocked() {
        given(this.users.tokenClaims("user-1")).willThrow(new ModuleException.Forbidden("blocked", "USER_BLOCKED"));

        assertThatExceptionOfType(OAuth2AuthenticationException.class).isThrownBy(() -> this.customizer.customize(
                context(OAuth2TokenType.ACCESS_TOKEN, JwsHeader.with(SignatureAlgorithm.RS256), JwtClaimsSet.builder())));
    }

    @Test
    @DisplayName("leaves ID tokens without authorization claims")
    void idToken() {
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder().subject("user-1");

        this.customizer.customize(context(new OAuth2TokenType("id_token"), JwsHeader.with(SignatureAlgorithm.RS256), claims));

        assertThat(claims.build().getClaims()).doesNotContainKeys("roles", "perms", "tv");
        assertThat(List.of()).isEmpty();
    }

    private static JwtEncodingContext context(OAuth2TokenType type, JwsHeader.Builder header, JwtClaimsSet.Builder claims) {
        return JwtEncodingContext.with(header, claims)
            .tokenType(type)
            .principal(new TestingAuthenticationToken("user-1", null))
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .build();
    }
}
