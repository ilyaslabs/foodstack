package io.github.ilyaslabs.foodstack.order.web;

import java.util.List;

import io.github.ilyaslabs.foodstack.order.api.OrderStatus;
import io.github.ilyaslabs.foodstack.order.core.application.OrderLiveUpdates;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPage;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPlacementService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderProjections;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import io.github.ilyaslabs.foodstack.order.core.application.PlaceOrder;
import io.github.ilyaslabs.foodstack.order.core.application.PlacedOrder;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { OrdersController.class, OrderStreamsController.class },
        properties = "module-switch.modules.order.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class OrdersControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    OrderPlacementService placement;

    @MockitoBean
    OrderService orders;

    @MockitoBean
    OrderProjections projections;

    @MockitoBean
    OrderLiveUpdates live;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("placing an order needs a signed-in customer and passes on the Idempotency-Key and language")
    void place() throws Exception {
        this.mvc.perform(post("/api/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        given(this.placement.place(eq("c1"), eq("k1"), any()))
            .willReturn(new PlacedOrder("o1", "7F3A-91C2", OrderStatus.PAYMENT_PENDING, "https://pay"));

        this.mvc.perform(post("/api/v1/orders").with(jwt().jwt((t) -> t.subject("c1"))).header("Idempotency-Key", "k1")
            .header("Accept-Language", "ur-PK,ur;q=0.9").contentType(MediaType.APPLICATION_JSON).content("""
                    {"fulfilment":"DELIVERY","paymentMethod":"CARD","address":{"lat":31.5,"lng":74.3,"addressLine":"12 Canal Road"},
                     "expectedTotalMinor":170000}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paymentUrl").value("https://pay"));

        ArgumentCaptor<PlaceOrder> request = ArgumentCaptor.forClass(PlaceOrder.class);
        verify(this.placement).place(eq("c1"), eq("k1"), request.capture());
        assertThat(request.getValue().fulfilment()).isEqualTo(Fulfilment.DELIVERY);
        assertThat(request.getValue().paymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(request.getValue().address().addressLine()).isEqualTo("12 Canal Road");
        assertThat(request.getValue().language()).isEqualTo("ur");
        assertThat(request.getValue().tipMinor()).isZero();
    }

    @Test
    @DisplayName("store steps go to the order service by name; problems come back as problem details")
    void actions() throws Exception {
        given(this.orders.storeAction("o1", "PICKED_UP", null, null, null))
            .willThrow(new ModuleException.Forbidden("The operator's rider reports this step", "OPERATOR_DELIVERY"));
        this.mvc.perform(post("/api/v1/orders/o1/actions/picked-up").with(jwt()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("OPERATOR_DELIVERY"));

        this.mvc.perform(post("/api/v1/orders/o1/actions/accept").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"prepMinutes\":25,\"storeRiderId\":\"r9\"}")).andExpect(status().isOk());
        verify(this.orders).storeAction("o1", "ACCEPT", 25, null, "r9");
    }

    @Test
    @DisplayName("support lists and refunds need order:manage")
    void support() throws Exception {
        given(this.orders.all(null, null, null, 20)).willReturn(new OrderPage(List.of(), null));
        this.mvc.perform(get("/api/v1/orders").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/orders").with(jwt().authorities(List.of(new SimpleGrantedAuthority("order:manage")))))
            .andExpect(status().isOk());
        this.mvc.perform(post("/api/v1/orders/o1/refund").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"amountMinor\":100,\"reason\":\"x\"}")).andExpect(status().isForbidden());
        this.mvc.perform(post("/api/v1/orders/o1/refund").with(jwt().authorities(List.of(new SimpleGrantedAuthority("order:manage"))))
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":100,\"reason\":\"Cold\"}"))
            .andExpect(status().isOk());
        verify(this.orders).refund("o1", 100, "Cold", null);
        this.mvc.perform(post("/api/v1/orders/o1/refund").with(jwt().authorities(List.of(new SimpleGrantedAuthority("order:manage"))))
            .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":50,\"reason\":\"Late\",\"fundedBy\":\"OPERATOR\"}"))
            .andExpect(status().isOk());
        verify(this.orders).refund("o1", 50, "Late", "OPERATOR");
    }

    @Test
    @DisplayName("streams check access before opening")
    void streams() throws Exception {
        given(this.orders.requireWatch("o9")).willThrow(new ModuleException.NotFound("No such order", "ORDER_NOT_FOUND"));
        this.mvc.perform(get("/api/v1/orders/o9/stream").with(jwt())).andExpect(status().isNotFound());
        this.mvc.perform(get("/api/v1/orders/o1/stream").with(jwt())).andExpect(status().isOk());
        verify(this.live).onOrder(eq("o1"), any());
    }
}
