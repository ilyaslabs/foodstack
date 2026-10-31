package io.github.ilyaslabs.foodstack.search.core.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenNowTest {

    private static final Map<String, List<TimeRange>> HOURS = Map.of("FRIDAY", List.of(new TimeRange("19:00", "02:00")));

    @ParameterizedTest(name = "{0} local, closed {1}: open {2}")
    @DisplayName("applies the same rules as the store module to the hours copied into the index")
    @CsvSource({ "2026-10-30T20:00, '', true", "2026-10-31T01:30, '', true", "2026-10-31T02:30, '', false",
            "2026-10-31T01:30, 2026-10-30, false", "2026-10-30T18:00, '', false" })
    void open(String local, String closedDate, boolean open) {
        Instant now = LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Karachi")).toInstant();
        List<String> closed = closedDate.isEmpty() ? List.of() : List.of(closedDate);
        assertThat(OpenNow.isOpen(HOURS, closed, false, "Asia/Karachi", now)).isEqualTo(open);
    }
}
