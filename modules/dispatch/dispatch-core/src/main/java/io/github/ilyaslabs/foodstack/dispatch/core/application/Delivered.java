package io.github.ilyaslabs.foodstack.dispatch.core.application;

/**
 * Proof of delivery from the rider (PRD DR-6).
 *
 * @param code the code the customer read out, when one is needed
 * @param photoUrl photo at the door, or {@code null}
 * @param cashCollectedMinor cash taken; {@code null} for the amount due
 */
public record Delivered(String code, String photoUrl, Long cashCollectedMinor) {

    @Override
    public String toString() {
        return "Delivered[photo=" + (this.photoUrl != null) + ", cash=" + this.cashCollectedMinor + "]";
    }
}
