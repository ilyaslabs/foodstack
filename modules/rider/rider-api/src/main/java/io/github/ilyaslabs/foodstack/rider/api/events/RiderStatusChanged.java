package io.github.ilyaslabs.foodstack.rider.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;
import io.github.ilyaslabs.foodstack.rider.api.RiderKind;
import io.github.ilyaslabs.foodstack.rider.api.RiderStatus;

/**
 * A rider went online or offline, or was suspended or reinstated. Dispatch withdraws open offers
 * from a rider who went offline.
 *
 * @param riderId the rider
 * @param kind operator or store rider
 * @param storeId the store of a store rider, or {@code null}
 * @param status active or suspended
 * @param online whether the rider is online now
 * @param actor who made the change (the rider, staff, or {@code system} for a lost connection)
 */
public record RiderStatusChanged(String riderId, RiderKind kind, String storeId, RiderStatus status, boolean online,
        String actor) implements ModuleEvent {

    @Override
    public String subject() {
        return this.riderId;
    }
}
