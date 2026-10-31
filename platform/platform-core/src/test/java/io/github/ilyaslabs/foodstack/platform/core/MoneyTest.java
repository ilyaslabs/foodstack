package io.github.ilyaslabs.foodstack.platform.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class MoneyTest {

    @Test
    @DisplayName("adds and subtracts in minor units")
    void arithmetic() {
        Money a = new Money(1050, "PKR");
        Money b = new Money(250, "PKR");

        assertThat(a.plus(b)).isEqualTo(new Money(1300, "PKR"));
        assertThat(b.minus(a)).isEqualTo(new Money(-800, "PKR"));
        assertThat(b.minus(a).isNegative()).isTrue();
    }

    @Test
    @DisplayName("refuses to mix currencies")
    void currencyMismatch() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(1, "USD").plus(new Money(1, "EUR")));
    }

    @Test
    @DisplayName("requires an ISO 4217 currency code")
    void currencyCode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Money(1, "DOLLAR"));
    }
}
