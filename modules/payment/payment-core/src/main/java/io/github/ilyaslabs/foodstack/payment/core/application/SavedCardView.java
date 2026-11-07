package io.github.ilyaslabs.foodstack.payment.core.application;

/**
 * A card the gateway keeps for the customer (PRD CO-5a): only what is printed on its face.
 *
 * @param id the gateway's id of the card
 * @param brand such as {@code visa}
 * @param last4 last four digits
 * @param expMonth expiry month
 * @param expYear expiry year
 */
public record SavedCardView(String id, String brand, String last4, int expMonth, int expYear) {
}
