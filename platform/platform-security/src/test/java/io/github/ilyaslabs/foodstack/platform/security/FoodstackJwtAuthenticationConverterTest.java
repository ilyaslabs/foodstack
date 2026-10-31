package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class FoodstackJwtAuthenticationConverterTest {

    @Test
    @DisplayName("turns permissions and roles from the token into authorities")
    void authorities() {
        Jwt jwt = Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject("user-1")
            .claim(TokenClaims.PERMISSIONS, List.of("settings:manage", "media:upload"))
            .claim(TokenClaims.ROLES, List.of("ADMIN"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        AbstractAuthenticationToken authentication = new FoodstackJwtAuthenticationConverter().convert(jwt);

        assertThat(authentication.getName()).isEqualTo("user-1");
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
            .containsExactlyInAnyOrder("settings:manage", "media:upload", "ROLE_ADMIN");
    }
}
