package io.github.ilyaslabs.foodstack.payment.core.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * An order's card payment in {@code payment_intents}: one per order, so the order id is the
 * {@code _id} (shard key {@code { _id: hashed }}, the order id as TDD 6.3 asks). Refunds are kept
 * inside; an order has only a few. Status moves only through conditional updates.
 *
 * @param id the order id
 * @param customerId who pays
 * @param status {@code CREATED}, {@code PENDING}, {@code SUCCEEDED}, {@code FAILED} or {@code EXPIRED}
 * @param amountMinor amount to charge
 * @param currency ISO 4217 code
 * @param merchantAccount whose account collects: {@code operator}, or a brand's or branch's merchant account id
 * @param gateway gateway id
 * @param sessionReference the gateway's id of the hosted page
 * @param paymentReference the gateway's id of the payment, for refunds
 * @param redirectUrl the hosted page
 * @param returnUrl where the customer goes after paying
 * @param cancelUrl where the customer goes when they go back
 * @param expiresAt when the page stops accepting payment
 * @param refundedMinor amount given back or being given back
 * @param refunds refunds in the order they were asked for
 * @param failureReason why it failed, or {@code null}
 * @param createdAt created
 * @param updatedAt last change
 */
@Document("payment_intents")
public record PaymentDocument(@Id String id, String customerId, String status, long amountMinor, String currency,
        String merchantAccount, String gateway, String sessionReference, String paymentReference, String redirectUrl,
        String returnUrl, String cancelUrl, Instant expiresAt, long refundedMinor, List<Refund> refunds, String failureReason,
        Instant createdAt, Instant updatedAt) {

    public static final String CREATED = "CREATED";

    public static final String PENDING = "PENDING";

    public static final String SUCCEEDED = "SUCCEEDED";

    public static final String FAILED = "FAILED";

    public static final String EXPIRED = "EXPIRED";

    /**
     * @param id refund id, also the idempotency key sent to the gateway
     * @param amountMinor amount
     * @param status {@code PENDING}, {@code SUCCEEDED} or {@code FAILED}
     * @param reason why it was asked for
     * @param gatewayReference the gateway's id of the refund
     * @param createdAt asked for at
     */
    public record Refund(String id, long amountMinor, String status, String reason, String gatewayReference, Instant createdAt) {
    }
}
