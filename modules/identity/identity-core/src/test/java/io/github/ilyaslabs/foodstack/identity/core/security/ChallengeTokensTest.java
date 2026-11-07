package io.github.ilyaslabs.foodstack.identity.core.security;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChallengeTokensTest {

    private final ImmutableJWKSet<SecurityContext> keys = new ImmutableJWKSet<>(SigningKeys.ephemeral());

    @Test
    @DisplayName("a challenge works for its step, for five minutes, and only with the same signing key")
    void challenge() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        ChallengeTokens tokens = new ChallengeTokens(this.keys, Clock.fixed(now, ZoneOffset.UTC));
        String token = tokens.issue("u1", ChallengeTokens.Step.TOTP, "WEB", "password");

        assertThat(tokens.verify(token, ChallengeTokens.Step.TOTP))
            .hasValue(new ChallengeTokens.Challenge("u1", ChallengeTokens.Step.TOTP, "WEB", "password"));
        assertThat(tokens.verify(token, ChallengeTokens.Step.TOTP_ENROLL)).isEmpty();
        assertThat(new ChallengeTokens(this.keys, Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC))
            .verify(token, ChallengeTokens.Step.TOTP)).isEmpty();
        assertThat(new ChallengeTokens(new ImmutableJWKSet<>(SigningKeys.ephemeral()), Clock.fixed(now, ZoneOffset.UTC))
            .verify(token, ChallengeTokens.Step.TOTP)).isEmpty();
        assertThat(tokens.verify(token.substring(0, token.length() - 2) + "xx", ChallengeTokens.Step.TOTP)).isEmpty();
        assertThat(tokens.verify("not-a-token", ChallengeTokens.Step.TOTP)).isEmpty();
    }

    @Test
    @DisplayName("a challenge is HS256 with its own type, so no module takes it for an ES256 access token")
    void notAnAccessToken() throws Exception {
        ChallengeTokens tokens = new ChallengeTokens(this.keys, Clock.systemUTC());
        com.nimbusds.jwt.SignedJWT jwt = com.nimbusds.jwt.SignedJWT.parse(tokens.issue("u1", ChallengeTokens.Step.TOTP, "WEB", "x"));

        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("HS256");
        assertThat(jwt.getHeader().getType().getType()).isEqualTo("fs-challenge+jwt");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isNull();
    }
}
