package io.github.ilyaslabs.foodstack.store.api;

import java.util.List;

/**
 * Lines to price and check against a branch's current menu.
 *
 * @param storeId the branch
 * @param lines cart lines, in cart order
 */
public record OrderValidationRequest(String storeId, List<OrderLineRequest> lines) {
}
