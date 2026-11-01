package io.github.ilyaslabs.foodstack.payment.api;

/**
 * Payment methods checkout may offer.
 *
 * @param cash cash on delivery or at pickup is on
 * @param card card payment is on and a gateway is set up
 * @param gateway the card gateway: {@code none}, {@code sandbox} or {@code stripe}
 */
public record PaymentOptionsView(boolean cash, boolean card, String gateway) {
}
