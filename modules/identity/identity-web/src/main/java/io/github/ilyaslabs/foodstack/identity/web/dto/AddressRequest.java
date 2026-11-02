package io.github.ilyaslabs.foodstack.identity.web.dto;

/**
 * A saved address as sent by the customer app ({@code POST} and {@code PUT /api/v1/me/addresses}).
 *
 * @param label {@code HOME}, {@code WORK} or {@code OTHER}
 * @param name own name for an {@code OTHER} address
 * @param lat latitude of the map pin
 * @param lng longitude of the map pin
 * @param addressLine street, house and area
 * @param instructions notes for the rider
 * @param preferred make it the address the apps start with
 * @param version the version edited ({@code PUT} only); a newer one is refused with {@code ADDRESS_CHANGED}
 */
public record AddressRequest(String label, String name, Double lat, Double lng, String addressLine, String instructions,
        Boolean preferred, Long version) {
}
