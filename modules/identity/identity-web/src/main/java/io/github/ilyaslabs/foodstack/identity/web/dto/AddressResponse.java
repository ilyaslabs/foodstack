package io.github.ilyaslabs.foodstack.identity.web.dto;

/**
 * A saved address (PRD ID-6).
 *
 * @param id address id
 * @param label {@code HOME}, {@code WORK} or {@code OTHER}
 * @param name own name for an {@code OTHER} address, or {@code null}
 * @param lat latitude
 * @param lng longitude
 * @param addressLine street, house and area
 * @param instructions notes for the rider, or {@code null}
 * @param preferred the address the apps start with
 * @param updatedAt last change, epoch seconds
 * @param version send back when editing
 */
public record AddressResponse(String id, String label, String name, double lat, double lng, String addressLine,
        String instructions, boolean preferred, long updatedAt, long version) {
}
