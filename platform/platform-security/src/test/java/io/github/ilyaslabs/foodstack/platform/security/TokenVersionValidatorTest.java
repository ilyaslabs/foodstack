package io.github.ilyaslabs.foodstack.platform.security;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TokenVersionValidatorTest {

    private final TokenRevocations revocations = mock(TokenRevocations.class);

    private final TokenVersionValidator validator = new TokenVersionValidator(this.revocations);

    @Test
    @DisplayName("rejects a token whose version has been revoked")
    void revoked() {
        given(this.revocations.isRevoked("user-1", 2)).willReturn(true);

        assertThat(this.validator.validate(token(2L)).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("accepts a current token")
    void current() {
        given(this.revocations.isRevoked("user-1", 3)).willReturn(false);

        assertThat(this.validator.validate(token(3L)).hasErrors()).isFalse();
    }

    @Test
    @DisplayName("accepts service tokens, which carry no version")
    void serviceToken() {
        assertThat(this.validator.validate(token(null)).hasErrors()).isFalse();
        verifyNoInteractions(this.revocations);
    }

    private static Jwt token(Long version) {
        Jwt.Builder builder = Jwt.withTokenValue("t")
            .header("alg", "ES256")
            .subject("user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600));
        if (version != null) {
            builder.claim(TokenClaims.TOKEN_VERSION, version);
        }
        return builder.build();
    }
}
