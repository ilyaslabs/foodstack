package io.github.ilyaslabs.foodstack.rider.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A rider in {@code rider_riders} (PRD DR-1); the id is the rider's user id. Shard key
 * {@code { _id: hashed }}. Presence fields ({@code online}, last position, active jobs, offer
 * counters) change with targeted updates; the profile changes under the version check.
 *
 * @param id the rider's user id
 * @param kind {@code OPERATOR} or {@code STORE}
 * @param storeId the store a store rider rides for, else {@code null}
 * @param brandId the brand of that store: a store rider rides for every branch of the brand (PRD
 *        BB-12); {@code null} for operator riders
 * @param name display name
 * @param phoneCipher phone, encrypted with the deployment's PII key
 * @param vehicle {@code BICYCLE}, {@code MOTORBIKE}, {@code SCOOTER} or {@code CAR}
 * @param plate number plate, or {@code null}
 * @param city where the rider works, as text until cities land (M4)
 * @param cityId the operator's city the rider works in, or {@code null} for any city
 * @param status {@code ACTIVE} or {@code SUSPENDED}
 * @param online whether the rider is taking jobs
 * @param idleSince when the rider went online or finished the last job
 * @param lat last known latitude, or {@code null}
 * @param lng last known longitude, or {@code null}
 * @param lastSeenAt last location ping, or {@code null}
 * @param activeOrderIds the deliveries the rider has now
 * @param offers offers made
 * @param accepted offers accepted
 * @param deliveries deliveries completed
 * @param createdAt created at
 * @param updatedAt last change
 * @param version optimistic lock of the profile
 */
@Document("rider_riders")
public record RiderDocument(@Id String id, String kind, String storeId, String brandId, String name, String phoneCipher, String vehicle,
        String plate, String city, String cityId, String status, boolean online, Instant idleSince, Double lat, Double lng,
        Instant lastSeenAt, List<String> activeOrderIds, long offers, long accepted, long deliveries, Instant createdAt,
        Instant updatedAt, @Version Long version) {

    public List<String> activeOrderIds() {
        return (this.activeOrderIds != null) ? this.activeOrderIds : List.of();
    }
}
