package io.github.ilyaslabs.foodstack.store.core.application;

import java.time.Instant;

/**
 * A commission that applies from a date (PRD FI-2: effective dates and history).
 *
 * @param commission the commission from then on; on a branch, {@code null} means it goes back to the brand's
 * @param effectiveFrom when it starts to apply to new orders
 * @param setBy who set it
 * @param setAt when it was set
 */
public record CommissionChange(Commission commission, Instant effectiveFrom, String setBy, Instant setAt) {
}
