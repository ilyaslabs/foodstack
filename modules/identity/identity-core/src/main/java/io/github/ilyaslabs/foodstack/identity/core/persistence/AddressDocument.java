package io.github.ilyaslabs.foodstack.identity.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Sharded;

/**
 * A customer's saved address in {@code identity_addresses} (PRD ID-6); shard key
 * {@code { userId: hashed }}, because a customer's addresses are always read together; {@link Sharded}
 * makes every save filter on it.
 *
 * @param id address id (UUIDv7)
 * @param userId whose address
 * @param label {@code HOME}, {@code WORK} or {@code OTHER}
 * @param name the customer's own name for an {@code OTHER} address, such as "Mum's place", or {@code null}
 * @param lat latitude of the map pin
 * @param lng longitude of the map pin
 * @param addressLine street, house and area as the customer wrote it
 * @param instructions notes for the rider (gate code, floor), or {@code null}
 * @param preferred whether this is the address the apps start with
 * @param createdAt created at
 * @param updatedAt last change
 * @param version optimistic lock
 */
@Document("identity_addresses")
@Sharded(shardKey = "userId", immutableKey = true)
public record AddressDocument(@Id String id, String userId, String label, String name, double lat, double lng,
        String addressLine, String instructions, boolean preferred, Instant createdAt, Instant updatedAt,
        @Version Long version) {

    public AddressDocument withPreferred(boolean value, Instant now) {
        return new AddressDocument(this.id, this.userId, this.label, this.name, this.lat, this.lng, this.addressLine,
                this.instructions, value, this.createdAt, now, this.version);
    }
}
