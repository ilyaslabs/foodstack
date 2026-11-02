package io.github.ilyaslabs.foodstack.rider.core.persistence;

import java.time.Instant;
import java.util.Set;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A delivery a rider has now, in {@code rider_assignments} (id = order id), fed by dispatch events:
 * who may follow the rider on the map and where the rider is heading. Shard key {@code { _id: hashed }}.
 *
 * @param id order id
 * @param riderId the rider
 * @param riderName the rider's name
 * @param vehicle the rider's vehicle
 * @param customerId the customer
 * @param storeId the store
 * @param storeOwnerIds the store's people at the time
 * @param pickupLat store latitude
 * @param pickupLng store longitude
 * @param dropoffLat customer latitude
 * @param dropoffLng customer longitude
 * @param pickedUp whether the rider has the food
 * @param assignedAt when the rider took it
 */
@Document("rider_assignments")
public record AssignmentDocument(@Id String id, String riderId, String riderName, String vehicle, String customerId,
        String storeId, Set<String> storeOwnerIds, double pickupLat, double pickupLng, double dropoffLat, double dropoffLng,
        boolean pickedUp, Instant assignedAt) {
}
