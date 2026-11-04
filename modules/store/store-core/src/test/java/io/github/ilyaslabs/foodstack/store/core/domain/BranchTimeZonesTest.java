package io.github.ilyaslabs.foodstack.store.core.domain;

import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BranchTimeZonesTest {

    private static final BranchTimeZones ZONES = new BranchTimeZones();

    @Test
    @DisplayName("a country with one zone answers without loading any boundaries")
    void oneZoneCountry() {
        assertThat(ZONES.zoneAt(31.52, 74.35, ZoneId.of("Asia/Karachi"))).isEqualTo(ZoneId.of("Asia/Karachi"));
    }

    @Test
    @DisplayName("a country with several zones finds the zone from the boundaries")
    void manyZoneCountry() {
        ZoneId deployment = ZoneId.of("America/New_York");

        assertThat(ZONES.zoneAt(34.05, -118.24, deployment)).isEqualTo(ZoneId.of("America/Los_Angeles"));
        assertThat(ZONES.zoneAt(41.88, -87.63, deployment)).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(ZONES.zoneAt(40.71, -74.0, deployment)).isEqualTo(deployment);
    }

    @Test
    @DisplayName("a point outside the country, or a deployment zone with no country, keeps the deployment's zone")
    void fallsBack() {
        assertThat(ZONES.zoneAt(51.5, -0.12, ZoneId.of("America/New_York"))).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(ZONES.zoneAt(31.52, 74.35, ZoneId.of("UTC"))).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    @DisplayName("parses what an owner typed, refusing names that are not zones")
    void parse() {
        assertThat(BranchTimeZones.parse(" Asia/Dubai ")).contains(ZoneId.of("Asia/Dubai"));
        assertThat(BranchTimeZones.parse("Mars/Base")).isEmpty();
        assertThat(BranchTimeZones.parse("")).isEmpty();
        assertThat(BranchTimeZones.parse(null)).isEmpty();
    }

}
