package io.github.ilyaslabs.foodstack.identity.core.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.ilyaslabs.foodstack.identity.api.Role;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

class AccessTokensTest {

    @Test
    @DisplayName("signs a 10 minute ES256 token with roles, permissions, token version, sign-in and branches")
    void mint() {
        JWKSource<SecurityContext> keys = new ImmutableJWKSet<>(SigningKeys.ephemeral());
        Instant now = Instant.now();
        AccessTokens tokens = new AccessTokens(keys, "https://example.com", Duration.ofMinutes(10),
                Clock.fixed(now, ZoneOffset.UTC));

        String token = tokens.mint("u1", "family-1", new UserService.TokenClaims(Set.of(Role.STORE_OWNER),
                Set.of("store:manage-own", "media:upload"), 4, Set.of("b1"), Set.of("s2", "s1")));

        Jwt jwt = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithm(SignatureAlgorithm.ES256).build().decode(token);
        assertThat(jwt.getSubject()).isEqualTo("u1");
        assertThat(jwt.getIssuer().toString()).isEqualTo("https://example.com");
        assertThat(jwt.getExpiresAt()).isEqualTo(now.plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        assertThat(jwt.getClaimAsString("sid")).isEqualTo("family-1");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("STORE_OWNER");
        assertThat(jwt.getClaimAsStringList("perms")).containsExactly("media:upload", "store:manage-own");
        assertThat(jwt.<Long>getClaim("tv")).isEqualTo(4L);
        assertThat(jwt.getClaimAsStringList("brandIds")).containsExactly("b1");
        assertThat(jwt.getClaimAsStringList("storeIds")).containsExactly("s1", "s2");
    }
}
