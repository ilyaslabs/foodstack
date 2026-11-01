package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * Postal address of a branch.
 *
 * @param line1 street and number
 * @param area neighbourhood or area, shown on store cards
 * @param city city
 * @param postalCode postal code, may be {@code null}
 */
public record Address(String line1, String area, String city, String postalCode) {
}
