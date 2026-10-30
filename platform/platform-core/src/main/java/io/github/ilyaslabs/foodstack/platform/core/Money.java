package io.github.ilyaslabs.foodstack.platform.core;

import java.util.Objects;

/**
 * An amount in minor units (cents, paisa) of one currency. Never floating point.
 *
 * @param minor the amount in minor units
 * @param currency the ISO 4217 currency code
 */
public record Money(long minor, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (currency.length() != 3) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 code: " + currency);
        }
    }

    public static Money zero(String currency) {
        return new Money(0, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minor, other.minor), this.currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(this.minor, other.minor), this.currency);
    }

    public boolean isNegative() {
        return this.minor < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Currency mismatch: " + this.currency + " and " + other.currency);
        }
    }
}
