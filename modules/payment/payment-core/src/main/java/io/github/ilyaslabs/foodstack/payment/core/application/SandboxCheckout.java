package io.github.ilyaslabs.foodstack.payment.core.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.evl.Flow;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
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
     * @param canSaveCard whether the customer may keep the card (PRD CO-5a)
     * @param savedCards the customer's saved cards
     */
    public record Page(String orderId, long amountMinor, String currency, String status, String cancelUrl,
            boolean canSaveCard, List<CardGateway.SavedCard> savedCards) {
    }

    @Flow(value = "payment-sandbox-page>load", label = "Payment and saved cards")
    public Page page(String orderId, String token) {
        PaymentDocument payment = require(orderId, token);
        String customer = payment.customerReference();
        return new Page(orderId, payment.amountMinor(), payment.currency(), payment.status(), payment.cancelUrl(),
                customer != null, (customer != null) ? this.gateway.cards(customer) : List.of());
    }

    /** Applies the customer's choice on the hosted page and returns where to send them next. */
    public String complete(String orderId, String token, boolean pay) {
        return complete(orderId, token, pay, false);
    }

    /**
     * Applies the customer's choice on the hosted page and returns where to send them next; a paid
     * page may keep a pretend Visa ending 4242 for the customer.
     */
    @Flow(value = "payment-sandbox-complete>pay", label = "Complete the sandbox payment")
    public String complete(String orderId, String token, boolean pay, boolean saveCard) {
        PaymentDocument payment = require(orderId, token);
        // @flow payment-sandbox-complete>pay>save-card: Save the card [when paid and asked]
        if (pay && saveCard && PaymentDocument.PENDING.equals(payment.status())) {
            this.gateway.saveCard(payment.customerReference(), "4242");
        }
        return finish(payment, pay);
    }

    /**
     * The app's pretend embedded fields (PRD CO-5): a saved card or a typed number pays, except a
     * number ending in 0002, which the "bank" declines. Returns where to send the customer next.
     */
    public String completeEmbedded(String orderId, String clientSecret, String cardNumber, String savedCardId,
            boolean saveCard) {
        // @flow payment-sandbox-card>secret: Check the client secret [on error PAYMENT_PAGE_NOT_FOUND]
        PaymentDocument payment = (this.gateway != null) ? this.mongo.findById(orderId, PaymentDocument.class) : null;
        if (payment == null || payment.clientSecret() == null || clientSecret == null
                || !java.security.MessageDigest.isEqual(payment.clientSecret().getBytes(StandardCharsets.UTF_8),
                        clientSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new ModuleException.NotFound("No such payment page", "PAYMENT_PAGE_NOT_FOUND");
        }
        // @flow payment-sandbox-card>card?: Saved card or a new number?
        if (savedCardId != null && !savedCardId.isBlank()) {
            // @flow payment-sandbox-card>card?>saved: Pay with the saved card [when saved card]
            String customer = payment.customerReference();
            if (customer == null || this.gateway.cards(customer).stream().noneMatch((c) -> c.id().equals(savedCardId))) {
                throw new ModuleException.BadRequest("That card is not saved", "CARD_NOT_FOUND");
            }
            return finish(payment, true);
        }
        // @flow payment-sandbox-card>card?>new: Check the number, save it if asked [when else] [on error CARD_INVALID]
        String digits = (cardNumber != null) ? cardNumber.replaceAll("[\\s-]", "") : "";
        if (!digits.matches("\\d{12,19}")) {
            throw new ModuleException.BadRequest("The card number is not valid", "CARD_INVALID",
                    Map.of("errors", Map.of("cardNumber", "must be 12 to 19 digits")));
        }
        boolean pay = !digits.endsWith("0002");
        if (pay && saveCard && PaymentDocument.PENDING.equals(payment.status())) {
            this.gateway.saveCard(payment.customerReference(), digits.substring(digits.length() - 4));
        }
        return finish(payment, pay);
    }

    @Flow(value = "payment-sandbox-complete>pay>finish", label = "Send the sandbox webhook to the payment service")
    @Flow(value = "payment-sandbox-card>finish", label = "Send the sandbox webhook to the payment service")
    private String finish(PaymentDocument payment, boolean pay) {
        Map.Entry<byte[], Map<String, String>> webhook = this.gateway.webhook(
                pay ? WebhookEvent.SUCCEEDED : WebhookEvent.FAILED, payment.id(), payment.sessionReference(),
                payment.amountMinor());
        this.payments.handleSandbox(payment, webhook.getKey(), webhook.getValue());
        return pay ? payment.returnUrl() : payment.cancelUrl();
    }

    @Flow(value = "payment-sandbox-page>load>token", label = "Check the page token", onError = "PAYMENT_PAGE_NOT_FOUND")
    @Flow(value = "payment-sandbox-complete>pay>token", label = "Check the page token", onError = "PAYMENT_PAGE_NOT_FOUND")
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
