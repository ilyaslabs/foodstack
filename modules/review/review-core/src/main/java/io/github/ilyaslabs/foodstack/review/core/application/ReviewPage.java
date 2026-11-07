package io.github.ilyaslabs.foodstack.review.core.application;

import java.util.List;

/**
 * @param items newest first
 * @param next the {@code before} of the next page, or {@code null} at the end
 */
public record ReviewPage(List<ReviewView> items, String next) {
}
