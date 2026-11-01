package io.github.ilyaslabs.foodstack.promotion.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackJwtAuthenticationConverter;
import io.github.ilyaslabs.foodstack.promotion.api.CouponRequest;
import io.github.ilyaslabs.foodstack.promotion.core.application.CouponService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PromotionApiImplTest {

    private final CouponService coupons = mock(CouponService.class);

    private final PromotionApiImpl api = new PromotionApiImpl(this.coupons);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("customers check, hold and give back only their own coupon uses")
    void ownUsesOnly() {
        CouponRequest request = new CouponRequest("SAVE", "c1", "s1", "b1", "Lahore", 1, 0, Fulfilment.PICKUP, null, true, "PKR");
        given(this.coupons.customerOf("o1")).willReturn("c1");

        callerWith("c2");
        assertThatThrownBy(() -> this.api.evaluate(request)).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.api.reserve("o1", request)).isInstanceOf(ModuleException.Forbidden.class);
        assertThatThrownBy(() -> this.api.release("o1")).isInstanceOf(ModuleException.Forbidden.class);
        verify(this.coupons, never()).release("o1");

        callerWith("c1");
        this.api.evaluate(request);
        this.api.reserve("o1", request);
        this.api.release("o1");
        verify(this.coupons).reserve("o1", request);
        verify(this.coupons).release("o1");
    }

    private static void callerWith(String subject, String... permissions) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions)).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }
}
