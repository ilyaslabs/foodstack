package io.github.ilyaslabs.foodstack.platform.security;

import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PiiCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final PiiCipher cipher = new PiiCipher(KEY);

    @Test
    @DisplayName("encrypts with a fresh IV each time and decrypts back")
    void roundTrip() {
        String first = this.cipher.encrypt("owner@example.com");
        String second = this.cipher.encrypt("owner@example.com");

        assertThat(first).isNotEqualTo(second).doesNotContain("owner");
        assertThat(this.cipher.decrypt(first)).isEqualTo("owner@example.com");
    }

    @Test
    @DisplayName("gives the same blind index for the same normalized value only")
    void blindIndex() {
        assertThat(this.cipher.blindIndex(" Owner@Example.com ")).isEqualTo(this.cipher.blindIndex("owner@example.com"));
        assertThat(this.cipher.blindIndex("other@example.com")).isNotEqualTo(this.cipher.blindIndex("owner@example.com"));
        assertThat(new PiiCipher(Base64.getEncoder().encodeToString(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
                13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32 }))
            .blindIndex("owner@example.com")).isNotEqualTo(this.cipher.blindIndex("owner@example.com"));
    }

    @Test
    @DisplayName("refuses keys shorter than 32 bytes")
    void shortKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PiiCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
