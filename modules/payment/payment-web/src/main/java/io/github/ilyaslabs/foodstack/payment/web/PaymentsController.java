package io.github.ilyaslabs.foodstack.payment.web;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.api.PaymentOptionsView;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    PaymentOptionsView options() {
        return this.payments.options();
    }

    @GetMapping("/api/v1/payments/gateway")
    @PreAuthorize("hasAnyAuthority('payment:manage', 'settings:manage')")
    Map<String, Object> gateway() {
        return Map.of("gateway", this.payments.gatewayId(), "configured", !"none".equals(this.payments.gatewayId()));
    }

    @PostMapping("/api/v1/payment-webhooks/{gateway}")
    ResponseEntity<Void> webhook(@PathVariable String gateway, @RequestBody byte[] body, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator()
            .forEachRemaining((name) -> headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name)));
        this.payments.handleWebhook(gateway, body, headers);
        return ResponseEntity.ok().build();
    }

    /** Webhooks of a brand's ({@code brands}) or branch's ({@code stores}) own merchant account (TDD 5.4). */
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
