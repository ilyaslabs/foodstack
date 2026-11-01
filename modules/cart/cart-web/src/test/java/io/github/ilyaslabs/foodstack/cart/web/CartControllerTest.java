package io.github.ilyaslabs.foodstack.cart.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.cart.api.CheckoutRequest;
import io.github.ilyaslabs.foodstack.cart.api.CheckoutView;
import io.github.ilyaslabs.foodstack.cart.core.application.AddLine;
import io.github.ilyaslabs.foodstack.cart.core.application.CartDetails;
import io.github.ilyaslabs.foodstack.cart.core.application.CartService;
import io.github.ilyaslabs.foodstack.cart.core.application.CheckoutService;
import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.platform.core.Fulfilment;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;
import io.github.ilyaslabs.foodstack.platform.security.ApiSecurityConfiguration;
import io.github.ilyaslabs.foodstack.platform.web.ProblemDetailsAdvice;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CartController.class, properties = "module-switch.modules.cart.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class })
class CartControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    CartService carts;

    @MockitoBean
    CheckoutService checkout;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("a cart needs a signed-in customer")
    void signedIn() throws Exception {
        this.mvc.perform(get("/api/v1/carts/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reads and changes the signed-in customer's own cart")
    void ownCart() throws Exception {
        CartDetails empty = new CartDetails("c1", 0, null, null, List.of(), 0, 0, List.of());
        given(this.carts.get("c1")).willReturn(empty);
        given(this.carts.addLine(eq("c1"), any())).willReturn(new CartDetails("c1", 1, null, "PKR", List.of(), 85_000, 2, List.of()));
        given(this.carts.setQuantity("c1", "l1", 0)).willReturn(empty);

        this.mvc.perform(get("/api/v1/carts/me").with(customer())).andExpect(status().isOk())
            .andExpect(jsonPath("$.itemCount").value(0));
        this.mvc.perform(post("/api/v1/carts/me/lines").with(customer()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"storeId":"s1","itemId":"i1","quantity":2,"optionIds":["o1"],"note":"no onion"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.subtotalMinor").value(85_000));
        verify(this.carts).addLine("c1", new AddLine("s1", "i1", 2, List.of("o1"), "no onion", false));
        this.mvc.perform(delete("/api/v1/carts/me/lines/l1").with(customer())).andExpect(status().isOk());
        this.mvc.perform(delete("/api/v1/carts/me").with(customer())).andExpect(status().isNoContent());
        verify(this.carts).clear("c1");
    }

    @Test
    @DisplayName("asks before mixing restaurants with a 409 the app can act on")
    void otherStore() throws Exception {
        given(this.carts.addLine(eq("c1"), any())).willThrow(new ModuleException.Conflict("other", "CART_OTHER_STORE",
                Map.of("storeId", "s1")));
        given(this.carts.setQuantity("c1", "l1", 3)).willReturn(new CartDetails("c1", 2, null, null, List.of(), 0, 3, List.of()));

        this.mvc.perform(post("/api/v1/carts/me/lines").with(customer()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"storeId\":\"s2\",\"itemId\":\"i9\",\"quantity\":1}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CART_OTHER_STORE"));
        this.mvc.perform(patch("/api/v1/carts/me/lines/l1").with(customer()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":3}"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("prices the cart for checkout with the delivery point, coupon, tip and method")
    void checkout() throws Exception {
        CheckoutView view = new CheckoutView("c1", 3, null, "PKR", Fulfilment.DELIVERY, List.of(), 100_000, null, null, 0, 0,
                15_000, 0, 0, 0, 115_000, 35, List.of(PaymentMethod.CASH), true, List.of());
        given(this.checkout.checkout(new CheckoutRequest("c1", Fulfilment.DELIVERY, new GeoPoint(31.5, 74.3), "WELCOME", 5000,
                PaymentMethod.CASH))).willReturn(view);

        this.mvc.perform(post("/api/v1/carts/me/checkout").with(customer()).contentType(MediaType.APPLICATION_JSON)
            .content("""
                    {"fulfilment":"DELIVERY","lat":31.5,"lng":74.3,"couponCode":"WELCOME","tipMinor":5000,"paymentMethod":"CASH"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalMinor").value(115_000))
            .andExpect(jsonPath("$.paymentMethods[0]").value("CASH"));
    }

    private static JwtRequestPostProcessor customer() {
        return jwt().jwt((token) -> token.subject("c1"));
    }
}
