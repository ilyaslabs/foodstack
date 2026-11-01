package io.github.ilyaslabs.foodstack.geo.core.domain;

import java.util.List;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GeoMathTest {

    private static final GeoPoint GULBERG = new GeoPoint(31.5204, 74.3587);

    @Test
    @DisplayName("measures great-circle distance")
    void distance() {
        // Lahore to Islamabad is about 270 km in a straight line
        assertThat(GeoMath.distanceMeters(GULBERG, new GeoPoint(33.6844, 73.0479))).isCloseTo(270_000, within(10_000.0));
        assertThat(GeoMath.distanceMeters(GULBERG, GULBERG)).isZero();
    }

    @Test
    @DisplayName("builds a circle whose points all lie on the radius")
    void circle() {
        List<GeoPoint> circle = GeoMath.circle(GULBERG, 3000, 48);

        assertThat(circle).hasSize(48);
        circle.forEach((point) -> assertThat(GeoMath.distanceMeters(GULBERG, point)).isCloseTo(3000, within(5.0)));
    }

    @Test
    @DisplayName("tells whether a point is inside a polygon")
    void contains() {
        List<GeoPoint> circle = GeoMath.circle(GULBERG, 3000, 48);

        assertThat(GeoMath.contains(circle, new GeoPoint(31.5300, 74.3600))).isTrue();
        assertThat(GeoMath.contains(circle, new GeoPoint(31.6000, 74.3600))).isFalse();
    }
}
