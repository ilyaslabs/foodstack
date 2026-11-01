package io.github.ilyaslabs.foodstack.search.core.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenWindowsTest {

    // Saturday 2026-10-31 14:00 in Lahore (UTC+5)
    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");

    @Test
    @DisplayName("turns weekly hours into UTC windows, runs overnight ranges past midnight and merges touching ones")
    void windows() {
        Map<String, List<TimeRange>> hours = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day.name(), List.of(new TimeRange("12:00", "15:00"), new TimeRange("15:00", "02:00")));
        }

        List<long[]> windows = OpenWindows.of(hours, List.of(), false, "Asia/Karachi", NOW, 2);

        // Saturday's lunch and night ranges touch and merge; the part before 'from' is cut off
        assertThat(windows.getFirst()).containsExactly(epoch("2026-10-31T09:00:00Z"), epoch("2026-10-31T21:00:00Z"));
        assertThat(windows.get(1)).containsExactly(epoch("2026-11-01T07:00:00Z"), epoch("2026-11-01T21:00:00Z"));
        assertThat(windows.getLast()[1]).isEqualTo(epoch("2026-11-02T09:00:00Z"));
        for (int i = 1; i < windows.size(); i++) {
            assertThat(windows.get(i)[0]).isGreaterThan(windows.get(i - 1)[1]);
        }
        assertThat(windows).allSatisfy((w) -> assertThat(w[1]).isGreaterThan(w[0]));
    }

    @Test
    @DisplayName("a holiday closes its day and the night starting on it; the safety switch closes everything")
    void closures() {
        Map<String, List<TimeRange>> hours = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            hours.put(day.name(), List.of(new TimeRange("18:00", "01:00")));
        }

        List<long[]> windows = OpenWindows.of(hours, List.of("2026-10-31"), false, "Asia/Karachi", NOW, 2);

        assertThat(windows).hasSize(1);
        assertThat(windows.getFirst()).containsExactly(epoch("2026-11-01T13:00:00Z"), epoch("2026-11-01T20:00:00Z"));
        assertThat(OpenWindows.of(hours, List.of(), true, "Asia/Karachi", NOW, 2)).isEmpty();
    }

    private static long epoch(String instant) {
        return Instant.parse(instant).getEpochSecond();
    }
}
