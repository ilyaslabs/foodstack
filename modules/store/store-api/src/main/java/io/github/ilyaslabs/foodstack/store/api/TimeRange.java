package io.github.ilyaslabs.foodstack.store.api;

/**
 * Opening time range of a day in the branch's local time, {@code HH:mm}. A range that closes at or
 * before it opens runs past midnight (for example 18:00 to 02:00).
 *
 * @param open opening time
 * @param close closing time
 */
public record TimeRange(String open, String close) {
}
