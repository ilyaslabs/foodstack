package io.github.ilyaslabs.foodstack.payment.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.core.application.CheckoutView;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.payment.core.application.SavedCardView;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment options for checkout (public), the gateway's webhooks (public, verified by signature),
 * and the gateway's state for the operator's settings page.
 */
@RestController
class PaymentsController {

    private final PaymentService payments;

    PaymentsController(PaymentService payments) {
        this.payments = payments;
    }

    @GetMapping("/api/v1/payments/options")
    @Flow(value = "payment-options", label = "Payment options", description = "Cash, card, card style and saved cards from ordering settings and the gateway")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/options", caller = "Customer web")
    PaymentOptionsView options() {
        return this.payments.options();
    }

    /** What the app's payment page needs for an embedded card payment (PRD CO-5). */
    @GetMapping("/api/v1/payments/{orderId}/checkout")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "payment-checkout-page", label = "Card payment page data", description = "What the embedded card form needs for one order")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/{orderId}/checkout", caller = "Customer web")
    CheckoutView checkout(@PathVariable String orderId) {
        return this.payments.checkout(orderId);
    }

    /** The signed-in customer's saved cards (PRD CO-5a). */
    @GetMapping("/api/v1/payments/cards")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "payment-saved-cards", label = "List saved cards")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/cards", caller = "Customer web")
    List<SavedCardView> cards() {
        return this.payments.cards();
    }

    @DeleteMapping("/api/v1/payments/cards/{cardId}")
    @PreAuthorize("isAuthenticated()")
    @Flow(value = "payment-remove-card", label = "Remove saved card")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/payments/cards/{cardId}", caller = "Customer web")
    ResponseEntity<Void> removeCard(@PathVariable String cardId) {
        this.payments.removeCard(cardId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/payments/gateway")
    @PreAuthorize("hasAnyAuthority('payment:manage', 'settings:manage')")
    @Flow(value = "payment-gateway-status", label = "Gateway status", description = "Which card gateway is set up")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/gateway", caller = "Back office")
    Map<String, Object> gateway() {
        return Map.of("gateway", this.payments.gatewayId(), "configured", !"none".equals(this.payments.gatewayId()));
    }

    @PostMapping("/api/v1/payment-webhooks/{gateway}")
    @Flow(value = "payment-webhook", label = "Gateway webhook", description = "Operator account: payment succeeded, failed or expired")
    @EVL.Endpoint(method = "POST", path = "/api/v1/payment-webhooks/{gateway}", caller = "Card gateway")
    ResponseEntity<Void> webhook(@PathVariable String gateway, @RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator()
            .forEachRemaining((name) -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        this.payments.handleWebhook(gateway, body, headers);
        return ResponseEntity.ok().build();
    }

    /** Webhooks of a brand's ({@code brands}) or branch's ({@code stores}) own merchant account (TDD 5.4). */
    @PostMapping("/api/v1/payment-webhooks/{gateway}/{scope}/{ownerId}")
    @Flow(value = "payment-owner-webhook", label = "Merchant account webhook", description = "A brand's or branch's own gateway account")
    @EVL.Endpoint(method = "POST", path = "/api/v1/payment-webhooks/{gateway}/{scope}/{ownerId}", caller = "Card gateway")
    ResponseEntity<Void> ownerWebhook(@PathVariable String gateway, @PathVariable String scope, @PathVariable String ownerId,
            @RequestBody byte[] body, HttpServletRequest request) {
        String owner = switch (scope) {
            case "brands" -> "BRAND";
            case "stores" -> "STORE";
            default -> throw new io.github.ilyaslabs.moduleswitch.ModuleException.NotFound("No such webhook address",
                    "GATEWAY_NOT_FOUND");
        };
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator()
            .forEachRemaining((name) -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        this.payments.handleOwnerWebhook(gateway, owner, ownerId, body, headers);
        return ResponseEntity.ok().build();
    }
}
