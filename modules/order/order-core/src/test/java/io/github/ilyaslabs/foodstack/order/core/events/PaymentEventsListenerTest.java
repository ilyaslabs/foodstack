package io.github.ilyaslabs.foodstack.order.core.events;

import java.util.Map;

import io.github.ilyaslabs.foodstack.order.core.application.OrderLifecycleService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPlacementService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderProjections;
import io.github.ilyaslabs.foodstack.order.core.persistence.OrderDocument;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.PaymentSucceeded;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundFailed;
import io.github.ilyaslabs.foodstack.payment.api.events.RefundSucceeded;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PaymentEventsListenerTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final OrderLifecycleService lifecycle = mock(OrderLifecycleService.class);

    private final OrderPlacementService placement = mock(OrderPlacementService.class);

    private final InboxGuard inbox = mock(InboxGuard.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final PaymentEventsListener listener = new PaymentEventsListener(this.lifecycle, this.placement, this.inbox,
            this.serviceCalls, this.json);

    @BeforeEach
    void runHandlers() {
        given(this.inbox.processOnce(any(), any(), any())).willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(2)).run();
            return true;
        });
        willAnswer((invocation) -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).given(this.serviceCalls).run(any());
    }

    @Test
    @DisplayName("a payment confirms the order once and empties the cart as the platform")
    void paid() {
        OrderDocument confirmed = mock(OrderDocument.class);
        given(confirmed.customerId()).willReturn("c1");
        given(confirmed.cartVersion()).willReturn(4L);
        given(this.lifecycle.paymentSucceeded("o1", 1_000)).willReturn(confirmed);

        this.listener.onEvent(envelope("e1", "foodstack.payment.PaymentSucceeded.v1",
                new PaymentSucceeded("o1", 1_000, "PKR", "sandbox", "ref")));

        verify(this.inbox).processOnce(eq("e1"), eq("order-payments"), any());
        verify(this.placement).clearCart("c1", 4);
    }

    @Test
    @DisplayName("a late payment empties nothing; failures and refund outcomes reach the order")
    void others() {
        this.listener.onEvent(envelope("e2", "foodstack.payment.PaymentSucceeded.v1",
                new PaymentSucceeded("o2", 1_000, "PKR", "sandbox", "ref")));
        verify(this.placement, never()).clearCart(any(), eq(0L));

        this.listener.onEvent(envelope("e3", "foodstack.payment.PaymentFailed.v1", new PaymentFailed("o3", "DECLINED")));
        verify(this.lifecycle).paymentFailed("o3", "DECLINED");
        this.listener.onEvent(envelope("e4", "foodstack.payment.RefundSucceeded.v1", new RefundSucceeded("o4", "r", 500, 500, "PKR")));
        verify(this.lifecycle).refundSucceeded("o4", 500);
        this.listener.onEvent(envelope("e5", "foodstack.payment.RefundFailed.v1", new RefundFailed("o5", "r", 500, "no")));
        verify(this.lifecycle).refundFailed("o5", 500, "no");
    }

    @Test
    @DisplayName("every order event refreshes that order's list rows")
    void projections() {
        OrderProjections projections = mock(OrderProjections.class);
        new OrderProjectionListener(projections, this.json).onEvent(envelope("e6", "foodstack.order.OrderReady.v1", Map.of()));
        verify(projections).refresh("o1");
    }

    private String envelope(String id, String type, Object data) {
        return this.json.writeValueAsString(Map.of("specversion", "1.0", "id", id, "source", "foodstack/payment", "type", type,
                "subject", "o1", "time", "2026-11-01T12:00:00Z", "data", data));
    }
}
