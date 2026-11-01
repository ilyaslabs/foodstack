package io.github.ilyaslabs.foodstack.payment.impl;

import java.util.List;

import io.github.ilyaslabs.foodstack.payment.api.CardSessionView;
import io.github.ilyaslabs.foodstack.payment.api.PaymentView;
import io.github.ilyaslabs.foodstack.payment.api.StartCardRequest;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
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

class PaymentApiImplTest {

    private final PaymentService payments = mock(PaymentService.class);

    private final PaymentApiImpl api = new PaymentApiImpl(this.payments);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("only the paying customer or the platform may start a card payment")
    void start() {
        StartCardRequest request = new StartCardRequest("o1", "A-1", "c1", 1_000, "PKR", "Spice Route", "r", "c");
        CardSessionView session = new CardSessionView("o1", "http://pay", 1L);
        given(this.payments.startCard(request)).willReturn(session);

        callerWith("c2");
        assertThatThrownBy(() -> this.api.startCard(request)).isInstanceOf(ModuleException.Forbidden.class);
        callerWith("c1");
        assertThat(this.api.startCard(request)).isSameAs(session);
        callerWith("foodstack-service", "module:service");
        assertThat(this.api.startCard(request)).isSameAs(session);
    }

    @Test
    @DisplayName("a payment is read by its customer, support, finance or the platform")
    void read() {
        PaymentView view = new PaymentView("o1", "SUCCEEDED", 1_000, 0, "PKR", "sandbox", null, 0);
        given(this.payments.customerOf("o1")).willReturn("c1");
        given(this.payments.get("o1")).willReturn(view);

        callerWith("c2");
        assertThatThrownBy(() -> this.api.get("o1")).isInstanceOf(ModuleException.Forbidden.class);
        callerWith("c1");
        assertThat(this.api.get("o1")).isSameAs(view);
        callerWith("support-1", "order:manage");
        assertThat(this.api.get("o1")).isSameAs(view);
        callerWith("finance-1", "payment:manage");
        assertThat(this.api.get("o1")).isSameAs(view);
        assertThat(this.api.get("o-unknown")).isNull();
    }

    private static void callerWith(String subject, String... permissions) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "ES256").subject(subject).claim("perms", List.of(permissions)).build();
        SecurityContextHolder.getContext().setAuthentication(new FoodstackJwtAuthenticationConverter().convert(jwt));
    }
}
