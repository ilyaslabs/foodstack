package io.github.ilyaslabs.foodstack.review.core.application;

import java.util.Map;

/**
 * A rider's private rating, for the operator (PRD RV-4).
 *
 * @param riderId the rider
 * @param count ratings
 * @param average mean, one decimal; 0 with none
 * @param tags how often each tag was chosen
 */
public record RiderRatingSummary(String riderId, long count, double average, Map<String, Long> tags) {
}
