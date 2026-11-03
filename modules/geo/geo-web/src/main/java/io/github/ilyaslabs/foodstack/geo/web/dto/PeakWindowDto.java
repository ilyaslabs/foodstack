package io.github.ilyaslabs.foodstack.geo.web.dto;

import java.time.DayOfWeek;
import java.util.List;

/**
 * Peak hours of operator delivery.
 *
 * @param days days it applies on, empty for every day
 * @param start {@code HH:mm}
 * @param end {@code HH:mm}; before the start for a window past midnight
 */
public record PeakWindowDto(List<DayOfWeek> days, String start, String end) {
}
