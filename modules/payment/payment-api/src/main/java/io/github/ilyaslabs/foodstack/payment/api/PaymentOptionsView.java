package io.github.ilyaslabs.foodstack.payment.api;

/**
 * Payment methods checkout may offer.
 *
 * @param cash cash on delivery or at pickup is on
 * @param card card payment is on and a gateway is set up
 * @param gateway the card gateway: {@code none}, {@code sandbox} or {@code stripe}
 * @param cardStyle {@code HOSTED_PAGE} or {@code EMBEDDED} (PRD CO-5)
 * @param savedCards customers may save cards with the gateway (PRD CO-5a)
 */
public record PaymentOptionsView(boolean cash, boolean card, String gateway, String cardStyle, boolean savedCards) {

    /** Options with the hosted payment page and no saved cards. */
    public PaymentOptionsView(boolean cash, boolean card, String gateway) {
        this(cash, card, gateway, "HOSTED_PAGE", false);
    }
}
