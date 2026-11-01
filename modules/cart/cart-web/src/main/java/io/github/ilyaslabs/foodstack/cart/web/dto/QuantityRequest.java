package io.github.ilyaslabs.foodstack.cart.web.dto;

/**
 * {@code PATCH /api/v1/carts/me/lines/{lineId}}.
 *
 * @param quantity new quantity, 0 removes the line
 */
public record QuantityRequest(int quantity) {
}
