package io.github.ilyaslabs.foodstack.store.core.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;

/**
 * Whether a branch takes orders at a moment (PRD ST-2, ST-2a): not temporarily closed, not on a
 * holiday closure, and inside one of the day's ranges. A range whose close is not after its open
 * runs past midnight and keeps the branch open into the next day.
 */
public final class OpeningHours {

    private static final Pattern HH_MM = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    private OpeningHours() {
    }

    /** A closed date range, both days included. */
    public record ClosedDays(LocalDate from, LocalDate to) {
    }

    public static boolean isOpen(Map<DayOfWeek, List<TimeRange>> hours, List<ClosedDays> closures, boolean temporarilyClosed,
            Instant now, ZoneId zone) {
        if (temporarilyClosed || hours == null) {
            return false;
        }
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        if (closedOn(closures, today)) {
            return false;
        }
        for (TimeRange range : hours.getOrDefault(local.getDayOfWeek(), List.of())) {
            LocalTime open = LocalTime.parse(range.open());
            LocalTime close = LocalTime.parse(range.close());
            if (close.isAfter(open) ? !time.isBefore(open) && time.isBefore(close) : !time.isBefore(open)) {
                return true;
            }
        }
        // Yesterday's overnight ranges still running after midnight (closures apply to the day they started)
        if (closedOn(closures, today.minusDays(1))) {
            return false;
        }
        for (TimeRange range : hours.getOrDefault(local.getDayOfWeek().minus(1), List.of())) {
            LocalTime open = LocalTime.parse(range.open());
            LocalTime close = LocalTime.parse(range.close());
            if (!close.isAfter(open) && time.isBefore(close)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a value is a valid {@code HH:mm} time. */
    public static boolean isTime(String value) {
        if (value == null || !HH_MM.matcher(value).matches()) {
            return false;
        }
        try {
            LocalTime.parse(value);
            return true;
        }
        catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean closedOn(List<ClosedDays> closures, LocalDate day) {
        return closures != null && closures.stream().anyMatch((c) -> !day.isBefore(c.from()) && !day.isAfter(c.to()));
    }
}
