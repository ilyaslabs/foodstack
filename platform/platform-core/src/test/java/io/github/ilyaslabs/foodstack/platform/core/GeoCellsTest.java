package io.github.ilyaslabs.foodstack.platform.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeoCellsTest {

    @Test
    @DisplayName("points in the same quarter degree share a cell; a step across the line changes it")
    void cellOfPoint() {
        assertThat(GeoCells.of(31.51, 74.34)).isEqualTo(GeoCells.of(31.60, 74.45));
        assertThat(GeoCells.of(31.49, 74.34)).isNotEqualTo(GeoCells.of(31.51, 74.34));
        assertThat(GeoCells.of(31.51, 74.34)).isEqualTo("486_1017");
    }

    @Test
    @DisplayName("a circle reads the cells it touches: one when small, its neighbours when it crosses a line")
    void around() {
        assertThat(GeoCells.around(31.625, 74.375, 1_000)).containsExactly(GeoCells.of(31.625, 74.375));
        assertThat(GeoCells.around(31.501, 74.501, 5_000)).hasSize(4);
        assertThat(GeoCells.around(31.5, 74.3, 20_000)).contains(GeoCells.of(31.5, 74.3)).hasSizeLessThanOrEqualTo(9);
    }

    @Test
    @DisplayName("a box larger than the cap is refused, so a map must zoom in")
    void cap() {
        assertThat(GeoCells.within(31.3, 74.1, 31.7, 74.6)).hasSize(6);
        assertThatThrownBy(() -> GeoCells.within(24, 61, 37, 77)).isInstanceOf(IllegalArgumentException.class);
    }
}
