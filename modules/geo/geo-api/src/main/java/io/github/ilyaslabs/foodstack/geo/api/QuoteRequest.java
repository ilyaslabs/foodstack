package io.github.ilyaslabs.foodstack.geo.api;

/**
 * A delivery to price.
 *
 * @param storeId the branch
 * @param storeLocation where the branch is
 * @param deliveryMode the branch's current delivery mode
 * @param destination the customer's address
 * @param storeTimezone IANA zone of the branch, which peak hours are read in; {@code null} for the deployment's
 * @param cityId the branch's city, whose own operator pricing applies when it has one; {@code null} for none
 */
public record QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination,
        String storeTimezone, String cityId) {

    public QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination) {
        this(storeId, storeLocation, deliveryMode, destination, null, null);
    }

    public QuoteRequest(String storeId, GeoPoint storeLocation, DeliveryMode deliveryMode, GeoPoint destination,
            String storeTimezone) {
        this(storeId, storeLocation, deliveryMode, destination, storeTimezone, null);
    }
}
