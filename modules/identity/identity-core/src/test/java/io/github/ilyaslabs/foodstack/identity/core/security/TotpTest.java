package io.github.ilyaslabs.foodstack.identity.core.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TotpTest {

    /** RFC 6238 appendix B, SHA-1 secret "12345678901234567890", last six digits. */
    private static final String RFC_SECRET = Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));

    @Test
    @DisplayName("matches the RFC 6238 test vectors")
    void rfcVectors() {
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(1111111109))).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, Instant.ofEpochSecond(2000000000))).isEqualTo("279037");
    }

    @Test
    @DisplayName("accepts the step before and after for clock drift, each code once, and nothing else")
    void verify() {
        String secret = Totp.newSecret();
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long step = now.getEpochSecond() / 30;

        assertThat(Totp.verify(secret, Totp.code(secret, now), now, 0)).hasValue(step);
        assertThat(Totp.verify(secret, Totp.code(secret, now.minusSeconds(30)), now, 0)).hasValue(step - 1);
        assertThat(Totp.verify(secret, Totp.code(secret, now.plusSeconds(30)), now, 0)).hasValue(step + 1);
        assertThat(Totp.verify(secret, Totp.code(secret, now.minusSeconds(90)), now, 0)).isEmpty();
        // used already
        assertThat(Totp.verify(secret, Totp.code(secret, now), now, step)).isEmpty();
        assertThat(Totp.verify(secret, "12345", now, 0)).isEmpty();
        assertThat(Totp.verify(secret, null, now, 0)).isEmpty();
    }

    @Test
    @DisplayName("base32 round-trips and the otpauth link names issuer and account")
    void base32AndUri() {
        byte[] bytes = { 1, 2, 3, (byte) 250, 99, 0, 7 };
        assertThat(Totp.base32Decode(Totp.base32(bytes))).containsExactly(bytes);
        assertThat(Totp.newSecret()).hasSize(32).matches("[A-Z2-7]+");
        assertThat(Totp.uri("Food Hub", "a@b.c", "ABC"))
            .isEqualTo("otpauth://totp/Food%20Hub:a%40b.c?secret=ABC&issuer=Food%20Hub&algorithm=SHA1&digits=6&period=30");
    }
}
