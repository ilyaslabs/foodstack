package io.github.ilyaslabs.foodstack.dispatch.core.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The delivery job of one delivery order, in {@code dispatch_jobs} (id = order id), shard key
 * {@code { _id: hashed }}. Changes are conditional on {@code revision}, so two replicas never move a
 * job twice. Open jobs are mirrored in {@code dispatch_active} for the sweeps and rider lists.
 *
 * @param id order id
 * @param orderNumber order number
 * @param customerId customer
 * @param customerName customer's name
 * @param contactPhoneCipher customer's phone, encrypted, or {@code null}
 * @param storeId branch
 * @param storeName branch name
 * @param storeOwnerIds the branch's people
 * @param mode {@code OPERATOR} or {@code SELF}
 * @param status {@link io.github.ilyaslabs.foodstack.dispatch.api.DeliveryStatus}
 * @param riderId rider on the job, or {@code null}
 * @param riderName rider's name
 * @param riderKind {@code OPERATOR} or {@code STORE}
 * @param vehicle rider's vehicle
 * @param pickupLat store latitude
 * @param pickupLng store longitude
 * @param pickupAddress store address
 * @param dropoffLat customer latitude
 * @param dropoffLng customer longitude
 * @param dropoffAddress customer address
 * @param instructions customer's directions, or {@code null}
 * @param deliveryChargeMinor what the customer paid for delivery
 * @param tipMinor tip for the rider
 * @param cashToCollectMinor cash to take at the door
 * @param currency ISO 4217 code
 * @param codeHash SHA-256 of the delivery code, or {@code null} when none is needed
 * @param language customer's language
 * @param readyBy when the store expects the food ready
 * @param searchFrom when offers may start
 * @param searchStartedAt when the first offer round ran, or {@code null}
 * @param radiusMeters current search radius
 * @param widenings how often the radius grew
 * @param offerRiderId rider holding the open offer, or {@code null}
 * @param offerExpiresAt when the open offer lapses
 * @param triedRiderIds riders who declined or let an offer lapse this round
 * @param alertedAt when support was told no rider was found, or {@code null}
 * @param assignedAt when the rider took it
 * @param atStoreAt when the rider reached the store
 * @param pickedUpAt when the rider took the food
 * @param closedAt delivered, cancelled or handed back at
 * @param closeReason why it was cancelled or handed back
 * @param proof {@code CODE}, {@code PHOTO}, {@code SUPPORT} or {@code NONE}
 * @param proofPhotoUrl photo at the door, or {@code null}
 * @param cashCollectedMinor cash the rider took
 * @param earningMinor what the rider earned
 * @param distanceMeters store to customer by road
 * @param payBasisMinor the normal delivery charge that operator rider pay is a share of; 0 in jobs made before it was kept
 * @param createdAt created at
 * @param updatedAt last change
 * @param revision number of changes
 */
@Document("dispatch_jobs")
public record JobDocument(@Id String id, String orderNumber, String customerId, String customerName, String contactPhoneCipher,
        String storeId, String storeName, Set<String> storeOwnerIds, String mode, String status, String riderId, String riderName,
        String riderKind, String vehicle, double pickupLat, double pickupLng, String pickupAddress, double dropoffLat,
        double dropoffLng, String dropoffAddress, String instructions, long deliveryChargeMinor, long tipMinor,
        long cashToCollectMinor, String currency, String codeHash, String language, Instant readyBy, Instant searchFrom,
        Instant searchStartedAt, int radiusMeters, int widenings, String offerRiderId, Instant offerExpiresAt,
        List<String> triedRiderIds, Instant alertedAt, Instant assignedAt, Instant atStoreAt, Instant pickedUpAt,
        Instant closedAt, String closeReason, String proof, String proofPhotoUrl, long cashCollectedMinor, long earningMinor,
        long distanceMeters, long payBasisMinor, Instant createdAt, Instant updatedAt, long revision) {

    public List<String> triedRiderIds() {
        return (this.triedRiderIds != null) ? this.triedRiderIds : List.of();
    }
}
