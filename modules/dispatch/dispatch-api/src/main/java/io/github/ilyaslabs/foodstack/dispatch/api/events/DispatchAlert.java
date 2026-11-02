package io.github.ilyaslabs.foodstack.dispatch.api.events;

import io.github.ilyaslabs.foodstack.dispatch.api.DeliverySummary;
import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * No operator rider took the job in time (PRD OF-8): support is alerted and can assign a rider or
 * hand the delivery back to the store. Sent once per job; the search goes on meanwhile.
 *
 * @param delivery the job after the change
 * @param reason {@code NO_RIDER}
 * @param searchingSeconds how long the search has run
 */
public record DispatchAlert(DeliverySummary delivery, String reason, long searchingSeconds) implements ModuleEvent {

    @Override
    public String subject() {
        return this.delivery.orderId();
    }
}
