package io.github.ilyaslabs.foodstack.order.impl;

import io.github.ilyaslabs.foodstack.order.api.OrderSummary;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class OrderApiImplTest {

    private final OrderService orders = mock(OrderService.class);

    private final OrderApiImpl api = new OrderApiImpl(this.orders);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("needs a caller; the order service decides who may read the order")
    void summary() {
        assertThatThrownBy(() -> this.api.getSummary("o1")).isInstanceOf(ModuleException.Unauthorized.class);
        OrderSummary summary = mock(OrderSummary.class);
        given(this.orders.summary("o1")).willReturn(summary);
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.TestingAuthenticationToken(
                "c1", null, "ROLE_USER"));
        assertThat(this.api.getSummary("o1")).isSameAs(summary);
    }
}
