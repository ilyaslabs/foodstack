package io.github.ilyaslabs.foodstack.order.api;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;

/**
 * What dispatch needs to deliver an accepted delivery order (TDD 5.3, 5.6).
 *
 * @param mode who delivers, fixed at placement (or {@code SELF} after support handed it back)
 * @param storeRiderId the store's own rider chosen when accepting, or {@code null}
 * @param pickup the store
 * @param pickupAddress the store's address
 * @param dropoff the customer
 * @param dropoffAddress the customer's address line
 * @param instructions the customer's directions for the rider, or {@code null}
 * @param customerName the customer's name
 * @param contactPhoneCipher the customer's phone, encrypted with the deployment's PII key, or {@code null}
 * @param deliveryChargeMinor what the customer pays for delivery, after any delivery discount
 * @param tipMinor the customer's tip for the rider
 * @param cashToCollectMinor what the rider takes in cash at the door (cash orders), else 0
 * @param currency ISO 4217 code
 * @param deliveryCode the 4-digit code the customer gives the rider (PRD DR-6), or {@code null}
 */
public record OrderDelivery(DeliveryMode mode, String storeRiderId, GeoPoint pickup, String pickupAddress, GeoPoint dropoff,
        String dropoffAddress, String instructions, String customerName, String contactPhoneCipher, long deliveryChargeMinor,
        long tipMinor, long cashToCollectMinor, String currency, String deliveryCode) {

    @Override
    public String toString() {
        return "OrderDelivery[mode=" + this.mode + ", storeRiderId=" + this.storeRiderId + "]";
    }
}
