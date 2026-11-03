package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

/**
 * Hours when operator delivery costs more (PRD DR-1b), in the deployment's time zone. A window
 * whose end is before its start runs past midnight; it belongs to the day it starts on.
 *
 * @param days the days it applies on; empty for every day
 * @param start first minute, {@code HH:mm}
 * @param end first minute after the window, {@code HH:mm}
 */
public record PeakWindow(List<DayOfWeek> days, String start, String end) {

    public PeakWindow {
        days = (days != null) ? List.copyOf(days) : List.of();
    }

    /** Whether the moment falls in the window. */
    public boolean contains(DayOfWeek day, LocalTime time) {
        LocalTime from = LocalTime.parse(this.start);
        LocalTime to = LocalTime.parse(this.end);
        if (!from.isAfter(to)) {
            return appliesOn(day) && !time.isBefore(from) && time.isBefore(to);
        }
        // past midnight: the evening part on the day itself, the early part on the next day
        return (appliesOn(day) && !time.isBefore(from)) || (appliesOn(day.minus(1)) && time.isBefore(to));
    }

    private boolean appliesOn(DayOfWeek day) {
        return this.days.isEmpty() || this.days.contains(day);
    }
}
