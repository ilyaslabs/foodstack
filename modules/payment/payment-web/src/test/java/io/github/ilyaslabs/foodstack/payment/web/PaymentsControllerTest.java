package io.github.ilyaslabs.foodstack.payment.web;

import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.core.application.CheckoutView;
import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.payment.core.application.SandboxCheckout;
import io.github.ilyaslabs.foodstack.payment.core.application.SavedCardView;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = { PaymentsController.class, SandboxPageController.class, MerchantAccountsController.class },
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
    MerchantAccounts accounts;

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
    @DisplayName("store accounts' webhooks arrive at the owner's address; unknown kinds of owner are 404")
    void ownerWebhooks() throws Exception {
        this.mvc.perform(post("/api/v1/payment-webhooks/stripe/stores/s1").content("{}")).andExpect(status().isOk());
        verify(this.payments).handleOwnerWebhook(eq("stripe"), eq("STORE"), eq("s1"), any(), any());
        this.mvc.perform(post("/api/v1/payment-webhooks/stripe/brands/b1").content("{}")).andExpect(status().isOk());
        verify(this.payments).handleOwnerWebhook(eq("stripe"), eq("BRAND"), eq("b1"), any(), any());
        this.mvc.perform(post("/api/v1/payment-webhooks/stripe/riders/r1").content("{}")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("owners connect and disconnect accounts with their keys; the keys are never sent back")
    void merchantAccounts() throws Exception {
        given(this.accounts.connect("BRAND", "b1", "sk_live_1234", "whsec_1")).willReturn(new MerchantAccounts.AccountView("a1",
                "BRAND", "b1", "stripe", "1234", "ACTIVE", null, java.time.Instant.ofEpochSecond(100), "u1"));
        given(this.accounts.view("s1")).willReturn(new MerchantAccounts.CollectionView("s1", "b1", true, "stripe", "DIRECT",
                false, null, null, "OPERATOR", null, "https://api/stores/s1", "https://api/brands/b1", false, true));
        var owner = jwt().authorities(List.of(new SimpleGrantedAuthority("payment:account")));

        this.mvc.perform(put("/api/v1/payments/merchant-accounts/brands/b1").contentType(MediaType.APPLICATION_JSON)
            .content("{\"secretKey\":\"sk_live_1234\",\"webhookSecret\":\"whsec_1\"}")).andExpect(status().isUnauthorized());
        this.mvc.perform(put("/api/v1/payments/merchant-accounts/brands/b1").with(jwt()).contentType(MediaType.APPLICATION_JSON)
            .content("{}")).andExpect(status().isForbidden());
        this.mvc.perform(put("/api/v1/payments/merchant-accounts/brands/b1").with(owner).contentType(MediaType.APPLICATION_JSON)
            .content("{\"secretKey\":\"sk_live_1234\",\"webhookSecret\":\"whsec_1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keyHint").value("1234"))
            .andExpect(jsonPath("$.verifiedAt").value(100))
            .andExpect(jsonPath("$.secretKey").doesNotExist());
        this.mvc.perform(delete("/api/v1/payments/merchant-accounts/stores/s1").with(owner)).andExpect(status().isNoContent());
        verify(this.accounts).disconnect("STORE", "s1");
        this.mvc.perform(get("/api/v1/payments/merchant-accounts").param("storeId", "s1").with(owner))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("DIRECT"))
            .andExpect(jsonPath("$.brandWebhookUrl").value("https://api/brands/b1"));
    }

    @Test
    @DisplayName("the sandbox page shows the amount with Pay and Decline, then sends the customer on")
    void sandboxPage() throws Exception {
        given(this.sandbox.page("o1", "tok")).willReturn(new SandboxCheckout.Page("o1", 125_050, "PKR", "PENDING", "https://c", true,
                List.of(new CardGateway.SavedCard("pm_1", "visa", "4242", 12, 2030))));
        given(this.sandbox.complete("o1", "tok", true, true)).willReturn("https://shop/orders/o1");
        given(this.sandbox.complete("o1", "tok", false, false)).willReturn("https://shop/checkout");
        given(this.sandbox.page("o2", "tok")).willReturn(new SandboxCheckout.Page("o2", 1_000, "PKR", "SUCCEEDED", "https://c", false, List.of()));
        given(this.sandbox.page("o3", "bad")).willThrow(new ModuleException.NotFound("No such payment page", "PAYMENT_PAGE_NOT_FOUND"));

        this.mvc.perform(get("/api/v1/payments/sandbox/o1").param("t", "tok")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(containsString("PKR 1250.50")))
            .andExpect(content().string(containsString("value=\"decline\"")))
            .andExpect(content().string(containsString("Saved: visa ending 4242")))
            .andExpect(content().string(containsString("name=\"save\"")));
        this.mvc.perform(get("/api/v1/payments/sandbox/o2").param("t", "tok")).andExpect(status().isOk())
            .andExpect(content().string(containsString("This payment is succeeded")))
            .andExpect(content().string(not(containsString("value=\"pay\""))));
        this.mvc.perform(get("/api/v1/payments/sandbox/o3").param("t", "bad")).andExpect(status().isNotFound());

        this.mvc.perform(post("/api/v1/payments/sandbox/o1").param("t", "tok").param("outcome", "pay").param("save", "true"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "https://shop/orders/o1"));
        this.mvc.perform(post("/api/v1/payments/sandbox/o1").param("t", "tok").param("outcome", "decline"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location", "https://shop/checkout"));
    }

    @Test
    @DisplayName("the app's embedded fields: checkout needs a signed-in customer, the sandbox card post is public")
    void embeddedCheckout() throws Exception {
        given(this.payments.checkout("o1")).willReturn(new CheckoutView("o1", "PENDING", 1_000, "PKR", "sandbox", null,
                "sess_secret_x", "https://shop/orders/o1", "https://c", 99, true, List.of()));
        given(this.sandbox.completeEmbedded("o1", "sess_secret_x", "4242424242424242", null, true))
            .willReturn("https://shop/orders/o1");

        this.mvc.perform(get("/api/v1/payments/o1/checkout")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/payments/o1/checkout").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.clientSecret").value("sess_secret_x"))
            .andExpect(jsonPath("$.canSaveCard").value(true));
        this.mvc.perform(post("/api/v1/payments/sandbox/o1/card").contentType(MediaType.APPLICATION_JSON)
            .content("{\"clientSecret\":\"sess_secret_x\",\"cardNumber\":\"4242424242424242\",\"saveCard\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.next").value("https://shop/orders/o1"));
    }

    @Test
    @DisplayName("a customer lists and removes their saved cards")
    void savedCards() throws Exception {
        given(this.payments.cards()).willReturn(List.of(new SavedCardView("pm_1", "visa", "4242", 12, 2030)));
        willThrow(new ModuleException.NotFound("No such card", "CARD_NOT_FOUND")).given(this.payments).removeCard("pm_x");

        this.mvc.perform(get("/api/v1/payments/cards")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/api/v1/payments/cards").with(jwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].last4").value("4242"));
        this.mvc.perform(delete("/api/v1/payments/cards/pm_1").with(jwt())).andExpect(status().isNoContent());
        verify(this.payments).removeCard("pm_1");
        this.mvc.perform(delete("/api/v1/payments/cards/pm_x").with(jwt())).andExpect(status().isNotFound());
    }
}
