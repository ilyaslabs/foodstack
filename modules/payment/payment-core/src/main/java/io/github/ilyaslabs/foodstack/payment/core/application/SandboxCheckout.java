package io.github.ilyaslabs.foodstack.payment.core.application;

import java.util.Map;

import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway.WebhookEvent;
import io.github.ilyaslabs.foodstack.payment.core.gateway.SandboxGateway;
import io.github.ilyaslabs.foodstack.payment.core.persistence.PaymentDocument;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The sandbox gateway's "hosted page" (development, tests and demos): shows the amount and turns
 * the customer's Pay or Decline into the signed webhook a real gateway would send.
 */
public class SandboxCheckout {

    private final SandboxGateway gateway;

    private final PaymentService payments;

    private final MongoTemplate mongo;

    /** @param gateway the sandbox gateway, or {@code null} when another gateway is configured */
    public SandboxCheckout(SandboxGateway gateway, PaymentService payments, MongoTemplate mongo) {
        this.gateway = gateway;
        this.payments = payments;
        this.mongo = mongo;
    }

    /**
     * What the page shows.
     *
     * @param orderId the order
     * @param amountMinor amount
     * @param currency ISO 4217 code
     * @param status payment status
     * @param cancelUrl where Back goes
     */
    public record Page(String orderId, long amountMinor, String currency, String status, String cancelUrl) {
    }

    public Page page(String orderId, String token) {
        PaymentDocument payment = require(orderId, token);
        return new Page(orderId, payment.amountMinor(), payment.currency(), payment.status(), payment.cancelUrl());
    }

    /** Applies the customer's choice and returns where to send them next. */
    public String complete(String orderId, String token, boolean pay) {
        PaymentDocument payment = require(orderId, token);
        Map.Entry<byte[], Map<String, String>> webhook = this.gateway.webhook(
                pay ? WebhookEvent.SUCCEEDED : WebhookEvent.FAILED, orderId, payment.sessionReference(), payment.amountMinor());
        this.payments.handleSandbox(payment, webhook.getKey(), webhook.getValue());
        return pay ? payment.returnUrl() : payment.cancelUrl();
    }

    private PaymentDocument require(String orderId, String token) {
        if (this.gateway == null || !this.gateway.validPageToken(orderId, token)) {
            throw new ModuleException.NotFound("No such payment page", "PAYMENT_PAGE_NOT_FOUND");
        }
        PaymentDocument payment = this.mongo.findById(orderId, PaymentDocument.class);
        if (payment == null) {
            throw new ModuleException.NotFound("No such payment page", "PAYMENT_PAGE_NOT_FOUND");
        }
        return payment;
    }
}
