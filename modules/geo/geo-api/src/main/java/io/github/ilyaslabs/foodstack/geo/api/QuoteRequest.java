package io.github.ilyaslabs.foodstack.geo.api;

/**
 * A delivery to price.
 *
 * @param storeId the branch
 * @param storeLocation where the branch is
 * @param deliveryMode the branch's current delivery mode
 * @param destination the customer's address
 * @param storeTimezone IANA zone of the branch, which peak hours are read in; {@code null} for the deployment's
 */
public record QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination,
        String storeTimezone) {

    public QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination) {
        this(storeId, storeLocation, deliveryMode, destination, null);
    }
}
