package io.github.ilyaslabs.foodstack.identity.core.application;

/**
 * A saved address as the customer typed it.
 *
 * @param label {@code HOME}, {@code WORK} or {@code OTHER}
 * @param name own name for an {@code OTHER} address, or {@code null}
 * @param lat latitude of the map pin
 * @param lng longitude of the map pin
 * @param addressLine street, house and area
 * @param instructions notes for the rider, or {@code null}
 * @param preferred make it the address the apps start with
 */
public record AddressInput(String label, String name, double lat, double lng, String addressLine, String instructions,
        boolean preferred) {
}
