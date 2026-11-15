package io.github.ilyaslabs.foodstack.payment.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
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

    @Flow(value = "payment-options", label = "Payment options", description = "Cash, card, embedded fields or hosted page, saved cards")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/options", caller = "Customer app")
    @GetMapping("/api/v1/payments/options")
    PaymentOptionsView options() {
        return this.payments.options();
    }

    /** What the app's payment page needs for an embedded card payment (PRD CO-5). */
    @Flow(value = "payment-checkout", label = "Card payment page data", description = "What the app needs for embedded card fields")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/{orderId}/checkout", caller = "Customer app")
    @GetMapping("/api/v1/payments/{orderId}/checkout")
    @PreAuthorize("isAuthenticated()")
    CheckoutView checkout(@PathVariable String orderId) {
        return this.payments.checkout(orderId);
    }

    /** The signed-in customer's saved cards (PRD CO-5a). */
    @Flow(value = "payment-cards", label = "My saved cards", description = "Kept at the gateway")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/cards", caller = "Customer app")
    @GetMapping("/api/v1/payments/cards")
    @PreAuthorize("isAuthenticated()")
    List<SavedCardView> cards() {
        return this.payments.cards();
    }

    @Flow(value = "payment-card-remove", label = "Remove a saved card", description = "At the gateway")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/payments/cards/{cardId}", caller = "Customer app")
    @DeleteMapping("/api/v1/payments/cards/{cardId}")
    @PreAuthorize("isAuthenticated()")
    ResponseEntity<Void> removeCard(@PathVariable String cardId) {
        this.payments.removeCard(cardId);
        return ResponseEntity.noContent().build();
    }

    @Flow(value = "payment-gateway", label = "Which card gateway is set up", description = "")
    @EVL.Endpoint(method = "GET", path = "/api/v1/payments/gateway", caller = "Back office (operator)")
    @GetMapping("/api/v1/payments/gateway")
    @PreAuthorize("hasAnyAuthority('payment:manage', 'settings:manage')")
    Map<String, Object> gateway() {
        return Map.of("gateway", this.payments.gatewayId(), "configured", !"none".equals(this.payments.gatewayId()));
    }

    @Flow(value = "payment-webhook", label = "Card payment webhook", description = "The gateway reports a payment on the operator's account")
    @EVL.Endpoint(method = "POST", path = "/api/v1/payment-webhooks/{gateway}", caller = "Card payment gateway")
    @PostMapping("/api/v1/payment-webhooks/{gateway}")
    ResponseEntity<Void> webhook(@PathVariable String gateway, @RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator()
            .forEachRemaining((name) -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        this.payments.handleWebhook(gateway, body, headers);
        return ResponseEntity.ok().build();
    }

    /** Webhooks of a brand's ({@code brands}) or branch's ({@code stores}) own merchant account (TDD 5.4). */
    @Flow(value = "payment-owner-webhook", label = "Owner account webhook", description = "The gateway reports a payment on a brand's or branch's own account")
    @EVL.Endpoint(method = "POST", path = "/api/v1/payment-webhooks/{gateway}/{scope}/{ownerId}", caller = "Card payment gateway")
    @PostMapping("/api/v1/payment-webhooks/{gateway}/{scope}/{ownerId}")
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
