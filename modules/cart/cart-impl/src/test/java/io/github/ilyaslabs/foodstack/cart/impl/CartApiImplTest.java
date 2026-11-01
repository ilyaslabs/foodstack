package io.github.ilyaslabs.foodstack.cart.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.application.CheckoutService;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class CartApiImplTest {

    private final CheckoutService checkout = mock(CheckoutService.class);

    private final CartService carts = mock(CartService.class);

    private final CartApiImpl api = new CartApiImpl(this.checkout, this.carts);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("only the cart's customer or the platform may price or empty a cart")
    void ownCartOnly() {
        CheckoutRequest request = new CheckoutRequest("c1", Fulfilment.PICKUP, null, null, 0, null);
        CheckoutView view = mock(CheckoutView.class);
        given(this.checkout.checkout(request)).willReturn(view);

        callerWith("c2");
        assertThatThrownBy(() -> this.api.checkout(request)).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.api.clear("c1", 3)).isInstanceOf(ModuleException.Forbidden.class);

        callerWith("c1");
        assertThat(this.api.checkout(request)).isSameAs(view);
        this.api.clear("c1", 3);
        verify(this.carts).clearIfVersion("c1", 3);

        callerWith("foodstack-service", "module:service");
        assertThat(this.api.checkout(request)).isSameAs(view);
    }

    private static void callerWith(String subject, String... permissions) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions)).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }
}
