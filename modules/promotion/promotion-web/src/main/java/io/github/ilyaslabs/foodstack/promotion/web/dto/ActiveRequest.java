package io.github.ilyaslabs.foodstack.promotion.web.dto;

/**
 * {@code POST /api/v1/promotions/{id}/active}.
 *
 * @param active switch the promotion on or off
 */
public record ActiveRequest(boolean active) {
}
