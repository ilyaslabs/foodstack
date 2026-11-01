package io.github.ilyaslabs.foodstack.cart.core.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutMathTest {

    @Test
    @DisplayName("shares round half up to a whole minor unit")
    void share() {
        assertThat(CheckoutMath.share(10_050, 1600)).isEqualTo(1608);
        assertThat(CheckoutMath.share(3, 5000)).isEqualTo(2);
        assertThat(CheckoutMath.share(1, 4999)).isZero();
        assertThat(CheckoutMath.share(-100, 1000)).isZero();
    }

    @Test
    @DisplayName("delivery is free from the threshold, service fee adds a fixed part and a share, tax ignores discounted food")
    void fees() {
        assertThat(CheckoutMath.deliveryCharge(15_000, 200_000L, 200_000)).isZero();
        assertThat(CheckoutMath.deliveryCharge(15_000, 200_000L, 199_999)).isEqualTo(15_000);
        assertThat(CheckoutMath.deliveryCharge(15_000, null, 1_000_000)).isEqualTo(15_000);
        assertThat(CheckoutMath.serviceFee(2_000, 250, 100_000)).isEqualTo(4_500);
        assertThat(CheckoutMath.tax(100_000, 20_000, 1600)).isEqualTo(12_800);
        assertThat(CheckoutMath.tax(100_000, 150_000, 1600)).isZero();
    }

    @Test
    @DisplayName("ETA is preparation plus riding time at 20 km/h, rounded up")
    void eta() {
        assertThat(CheckoutMath.etaMinutes(20, 0)).isEqualTo(20);
        assertThat(CheckoutMath.etaMinutes(20, 5_000)).isEqualTo(35);
        assertThat(CheckoutMath.etaMinutes(20, 5_100)).isEqualTo(36);
    }
}
