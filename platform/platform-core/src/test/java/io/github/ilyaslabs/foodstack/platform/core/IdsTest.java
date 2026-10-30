package io.github.ilyaslabs.foodstack.platform.core;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdsTest {

    @Test
    @DisplayName("generates time-ordered version 7 UUIDs")
    void generatesVersion7() {
        // when
        String first = Ids.newId();
        String second = Ids.newId();

        // then
        assertThat(UUID.fromString(first).version()).isEqualTo(7);
        assertThat(first).isLessThan(second);
        assertThat(Ids.isValid(first)).isTrue();
    }

    @Test
    @DisplayName("rejects values that are not UUIDs")
    void rejectsInvalid() {
        assertThat(Ids.isValid(null)).isFalse();
        assertThat(Ids.isValid("not-a-uuid")).isFalse();
        assertThat(Ids.isValid("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")).isFalse();
    }
}
