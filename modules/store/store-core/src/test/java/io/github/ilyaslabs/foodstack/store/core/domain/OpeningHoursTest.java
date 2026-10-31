package io.github.ilyaslabs.foodstack.store.core.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpeningHoursTest {

    private static final ZoneId KARACHI = ZoneId.of("Asia/Karachi");

    // Friday lunch, Friday and Saturday late night until 02:00
    private static final Map<DayOfWeek, List<TimeRange>> HOURS = Map.of(DayOfWeek.FRIDAY,
            List.of(new TimeRange("12:00", "15:00"), new TimeRange("19:00", "02:00")), DayOfWeek.SATURDAY,
            List.of(new TimeRange("19:00", "02:00")));

    @ParameterizedTest(name = "{0} local is open: {1}")
    @DisplayName("knows when a branch is open, including ranges past midnight, in the deployment's timezone")
    @CsvSource({ "2026-10-30T11:59, false", "2026-10-30T12:00, true", "2026-10-30T14:59, true", "2026-10-30T15:00, false",
            "2026-10-30T23:30, true", "2026-10-31T01:59, true", "2026-10-31T02:00, false", "2026-10-31T13:00, false",
            "2026-11-01T01:00, true", "2026-11-01T19:30, false" })
    void open(String localTime, boolean open) {
        Instant now = java.time.LocalDateTime.parse(localTime).atZone(KARACHI).toInstant();
        assertThat(OpeningHours.isOpen(HOURS, List.of(), false, now, KARACHI)).isEqualTo(open);
    }

    @ParameterizedTest(name = "{0} local is open: {1}")
    @DisplayName("holiday closures close the day, and the night that started on it")
    @CsvSource({ "2026-10-30T13:00, false", "2026-10-31T01:00, false", "2026-10-31T20:00, true" })
    void closures(String localTime, boolean open) {
        Instant now = java.time.LocalDateTime.parse(localTime).atZone(KARACHI).toInstant();
        List<OpeningHours.ClosedDays> closed = List.of(
                new OpeningHours.ClosedDays(LocalDate.parse("2026-10-30"), LocalDate.parse("2026-10-30")));
        assertThat(OpeningHours.isOpen(HOURS, closed, false, now, KARACHI)).isEqualTo(open);
    }

    @ParameterizedTest(name = "\"{0}\" is a time: {1}")
    @DisplayName("accepts HH:mm times only")
    @CsvSource({ "00:00, true", "23:59, true", "24:00, false", "9:00, false", "12:60, false", "noon, false" })
    void isTime(String value, boolean valid) {
        assertThat(OpeningHours.isTime(value)).isEqualTo(valid);
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the temporarily-closed switch wins over the timetable")
    void temporarilyClosed() {
        Instant fridayLunch = java.time.LocalDateTime.parse("2026-10-30T13:00").atZone(KARACHI).toInstant();
        assertThat(OpeningHours.isOpen(HOURS, List.of(), true, fridayLunch, KARACHI)).isFalse();
    }
}
