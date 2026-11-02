package io.github.ilyaslabs.foodstack.rider.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * A sampled rider position in {@code rider_locations}, kept for the operator's retention days
 * (TTL on {@code expireAt}) to settle disputes. Shard key {@code { riderId: hashed }}.
 *
 * @param id sample id
 * @param riderId the rider
 * @param orderIds the deliveries the rider had at the time
 * @param lat latitude
 * @param lng longitude
 * @param accuracyMeters reported accuracy, or {@code null}
 * @param at when the phone took it
 * @param expireAt when MongoDB deletes it
 */
@Document("rider_locations")
@Sharded(shardKey = "riderId", immutableKey = true)
public record LocationDocument(@Id String id, String riderId, java.util.List<String> orderIds, double lat, double lng,
        Double accuracyMeters, Instant at, Instant expireAt) {
}
