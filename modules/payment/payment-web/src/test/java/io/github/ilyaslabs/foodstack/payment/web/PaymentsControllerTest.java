package io.github.ilyaslabs.foodstack.payment.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.payment.core.application.SandboxCheckout;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { PaymentsController.class, SandboxPageController.class },
        properties = "module-switch.modules.payment.mode=local")
@Import({ ApiSecurityConfiguration.class, ProblemDetailsAdvice.class, PaymentWebConfiguration.class })
class PaymentsControllerTest {

    @SpringBootApplication
    static class App {
    }

    @MockitoBean
    PaymentService payments;

    @MockitoBean
    SandboxCheckout sandbox;

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("checkout options are public; the gateway state is for finance and settings staff")
    void options() throws Exception {
        given(this.payments.options()).willReturn(new PaymentOptionsView(true, true, "sandbox"));
        given(this.payments.gatewayId()).willReturn("sandbox");

        this.mvc.perform(get("/api/v1/payments/options")).andExpect(status().isOk())
            .andExpect(jsonPath("$.card").value(true))
            .andExpect(jsonPath("$.gateway").value("sandbox"));
        this.mvc.perform(get("/api/v1/payments/gateway")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/payments/gateway").with(jwt())).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/v1/payments/gateway").with(jwt().authorities(List.of(new SimpleGrantedAuthority("payment:manage")))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.configured").value(true));
    }

    @Test
    @DisplayName("webhooks are public and passed on with their raw body and lower-case headers; bad signatures get 400")
    void webhooks() throws Exception {
        this.mvc.perform(post("/api/v1/payment-webhooks/stripe").contentType(MediaType.APPLICATION_JSON).content("{\"a\":1}")
            .header("Stripe-Signature", "t=1,v1=x")).andExpect(status().isOk());
        verify(this.payments).handleWebhook(eq("stripe"), eq("{\"a\":1}".getBytes()),
                org.mockito.ArgumentMatchers.argThat((Map<String, String> h) -> "t=1,v1=x".equals(h.get("stripe-signature"))));

        willThrow(new ModuleException.BadRequest("bad", "WEBHOOK_INVALID")).given(this.payments)
            .handleWebhook(eq("sandbox"), any(), any());
        this.mvc.perform(post("/api/v1/payment-webhooks/sandbox").content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("WEBHOOK_INVALID"));
    }

    @Test
    @DisplayName("the sandbox page shows the amount with Pay and Decline, then sends the customer on")
    void sandboxPage() throws Exception {
        given(this.sandbox.page("o1", "tok")).willReturn(new SandboxCheckout.Page("o1", 125_050, "PKR", "PENDING", "https://c"));
        given(this.sandbox.complete("o1", "tok", true)).willReturn("https://shop/orders/o1");
        given(this.sandbox.complete("o1", "tok", false)).willReturn("https://shop/checkout");
        given(this.sandbox.page("o2", "tok")).willReturn(new SandboxCheckout.Page("o2", 1_000, "PKR", "SUCCEEDED", "https://c"));
        given(this.sandbox.page("o3", "bad")).willThrow(new ModuleException.NotFound("No such payment page", "PAYMENT_PAGE_NOT_FOUND"));

        this.mvc.perform(get("/api/v1/payments/sandbox/o1").param("t", "tok")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(containsString("PKR 1250.50")))
            .andExpect(content().string(containsString("value=\"decline\"")));
        this.mvc.perform(get("/api/v1/payments/sandbox/o2").param("t", "tok")).andExpect(status().isOk())
            .andExpect(content().string(containsString("This payment is succeeded")))
            .andExpect(content().string(not(containsString("value=\"pay\""))));
        this.mvc.perform(get("/api/v1/payments/sandbox/o3").param("t", "bad")).andExpect(status().isNotFound());

        this.mvc.perform(post("/api/v1/payments/sandbox/o1").param("t", "tok").param("outcome", "pay"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "https://shop/orders/o1"));
        this.mvc.perform(post("/api/v1/payments/sandbox/o1").param("t", "tok").param("outcome", "decline"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "https://shop/checkout"));
    }
}
