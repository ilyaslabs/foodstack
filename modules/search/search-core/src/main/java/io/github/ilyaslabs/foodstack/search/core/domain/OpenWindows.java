package io.github.ilyaslabs.foodstack.search.core.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.store.api.TimeRange;

/**
 * The concrete times a branch is open over the coming days, as epoch-second windows, so the
 * search index can tell open from closed at query time without knowing time zones (TDD 11). Same
 * rules as {@link OpenNow}: a range whose close is not after its open runs past midnight, and a
 * holiday closes its day and the night that starts on it. Windows are sorted and merged, so no two
 * touch: start[i] &lt; end[i] &lt; start[i+1].
 */
public final class OpenWindows {

    private OpenWindows() {
    }

    /**
     * @param from start of the period (a window already open at {@code from} is included)
     * @param days length of the period in days
     * @return windows as {@code [start, end)} pairs in epoch seconds; empty when switched off
     */
    public static List<long[]> of(Map<String, List<TimeRange>> hours, List<String> closedDates, boolean temporarilyClosed,
            String timezone, Instant from, int days) {
        if (temporarilyClosed || hours == null) {
            return List.of();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timezone);
        }
        catch (RuntimeException ex) {
            zone = ZoneId.of("UTC");
        }
        List<String> closed = (closedDates != null) ? closedDates : List.of();
        long periodStart = from.getEpochSecond();
        long periodEnd = from.plusSeconds(days * 86_400L).getEpochSecond();
        LocalDate first = from.atZone(zone).toLocalDate().minusDays(1);
        List<long[]> raw = new ArrayList<>();
        for (int i = 0; i <= days + 1; i++) {
            LocalDate day = first.plusDays(i);
            if (closed.contains(day.toString())) {
                continue;
            }
            List<TimeRange> ranges = hours.get(day.getDayOfWeek().name());
            if (ranges == null) {
                continue;
            }
            for (TimeRange range : ranges) {
                LocalTime open = LocalTime.parse(range.open());
                LocalTime close = LocalTime.parse(range.close());
                long start = day.atTime(open).atZone(zone).toEpochSecond();
                LocalDate closeDay = close.isAfter(open) ? day : day.plusDays(1);
                long end = closeDay.atTime(close).atZone(zone).toEpochSecond();
                start = Math.max(start, periodStart);
                end = Math.min(end, periodEnd);
                if (end > start) {
                    raw.add(new long[] { start, end });
                }
            }
        }
        raw.sort(Comparator.comparingLong((long[] w) -> w[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] window : raw) {
            if (!merged.isEmpty() && window[0] <= merged.getLast()[1]) {
                merged.getLast()[1] = Math.max(merged.getLast()[1], window[1]);
            }
            else {
                merged.add(window);
            }
        }
        return merged;
    }
}
