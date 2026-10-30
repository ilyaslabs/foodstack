package io.github.ilyaslabs.foodstack.platform.web;

import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ETagsTest {

    @Test
    @DisplayName("round-trips a resource version through ETag and If-Match")
    void roundTrip() {
        String tag = ETags.of("branding", 7);

        assertThat(tag).isEqualTo("\"branding-7\"");
        assertThat(ETags.versionFromIfMatch("branding", tag)).isEqualTo(7L);
        assertThat(ETags.versionFromIfMatch("branding", "W/" + tag)).isEqualTo(7L);
        assertThat(ETags.versionFromIfMatch("branding", null)).isNull();
    }

    @Test
    @DisplayName("rejects an If-Match naming another resource")
    void otherResource() {
        assertThatThrownBy(() -> ETags.versionFromIfMatch("branding", "\"locale-3\""))
            .isInstanceOfSatisfying(ModuleException.BadRequest.class,
                    (ex) -> assertThat(ex.code()).isEqualTo("INVALID_IF_MATCH"));
    }

    @Test
    @DisplayName("accepts the tag a compressing proxy rewrote")
    void proxyRewrittenTag() {
        assertThat(ETags.versionFromIfMatch("branding", "\"branding-7-zstd\"")).isEqualTo(7L);
        assertThat(ETags.matches("\"branding-7\"", "W/\"branding-7-gzip\"")).isTrue();
        assertThat(ETags.matches("\"branding-7\"", "\"branding-8\"")).isFalse();
        assertThat(ETags.matches("\"branding-7\"", null)).isFalse();
    }
}
