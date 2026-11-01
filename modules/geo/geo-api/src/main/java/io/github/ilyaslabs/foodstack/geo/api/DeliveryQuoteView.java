package io.github.ilyaslabs.foodstack.geo.api;

/**
 * The price of a delivery. Amounts are in minor units of the deployment currency.
 *
 * @param deliverable whether the branch delivers to the address
 * @param reason why not: {@code OUT_OF_AREA}, {@code TOO_FAR} or {@code OPERATOR_DELIVERY_OFF}; {@code null} when deliverable
 * @param chargeMinor the delivery charge the customer pays
 * @param minOrderMinor smallest basket subtotal accepted, 0 for none
 * @param freeDeliveryAboveMinor subtotal from which delivery is free, or {@code null}
 * @param distanceMeters estimated road distance
 * @param zoneId the matching zone of a self-delivery branch, or {@code null}
 * @param deliveryMode who delivers
 */
public record DeliveryQuoteView(boolean deliverable, String reason, long chargeMinor, long minOrderMinor,
        Long freeDeliveryAboveMinor, long distanceMeters, String zoneId, DeliveryMode deliveryMode) {

    public static final String OUT_OF_AREA = "OUT_OF_AREA";

    public static final String TOO_FAR = "TOO_FAR";

    public static final String OPERATOR_DELIVERY_OFF = "OPERATOR_DELIVERY_OFF";
}
