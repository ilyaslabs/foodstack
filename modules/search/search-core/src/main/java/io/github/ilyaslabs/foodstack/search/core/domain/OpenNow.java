package io.github.ilyaslabs.foodstack.search.core.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;

/**
 * Whether an indexed branch takes orders right now, from the hours copied into the index. Ranges
 * whose close is not after their open run past midnight; a holiday closes its day and the night
 * that starts on it (same rules as the store module).
 */
public final class OpenNow {

    private OpenNow() {
    }

    public static boolean isOpen(Map<String, List<TimeRange>> hours, List<String> closedDates, boolean temporarilyClosed,
            String timezone, Instant now) {
        if (temporarilyClosed || hours == null) {
            return false;
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        }
        catch (RuntimeException ex) {
            zone = ZoneId.of("UTC");
        }
        ZonedDateTime local = now.atZone(zone);
        LocalDate today = local.toLocalDate();
        LocalTime time = local.toLocalTime();
        List<String> closed = (closedDates != null) ? closedDates : List.of();
        if (!closed.contains(today.toString())) {
            for (TimeRange range : ranges(hours, local.getDayOfWeek())) {
                LocalTime open = LocalTime.parse(range.open());
                LocalTime close = LocalTime.parse(range.close());
                if (close.isAfter(open) ? !time.isBefore(open) && time.isBefore(close) : !time.isBefore(open)) {
                    return true;
                }
            }
        }
        if (closed.contains(today.minusDays(1).toString())) {
            return false;
        }
        for (TimeRange range : ranges(hours, local.getDayOfWeek().minus(1))) {
            LocalTime open = LocalTime.parse(range.open());
            LocalTime close = LocalTime.parse(range.close());
            if (!close.isAfter(open) && time.isBefore(close)) {
                return true;
            }
        }
        return false;
    }

    private static List<TimeRange> ranges(Map<String, List<TimeRange>> hours, DayOfWeek day) {
        List<TimeRange> ranges = hours.get(day.name());
        return (ranges != null) ? ranges : List.of();
    }
}
