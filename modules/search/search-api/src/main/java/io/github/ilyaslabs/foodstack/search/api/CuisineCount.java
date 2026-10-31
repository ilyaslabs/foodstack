package io.github.ilyaslabs.foodstack.search.api;

/**
 * A cuisine chip with how many matching branches serve it.
 *
 * @param cuisine cuisine tag
 * @param count matching branches
 */
public record CuisineCount(String cuisine, long count) {
}
