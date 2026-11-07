package io.github.ilyaslabs.foodstack.payment.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A brand's or branch's own merchant account in {@code payment_merchant_accounts} (PRD DP-3, BB-9,
 * TDD 5.4). Every connection is a new document with its own id, which payments keep, so refunds
 * use the credentials the money came in with even after the owner connects another account; the
 * owner's previous account is then {@code REVOKED} and {@code current} moves to the new one (a
 * partial unique index keeps one current account per owner). Credentials are encrypted with the PII
 * key and never returned.
 *
 * @param id the account
 * @param scope {@link #BRAND} or {@link #STORE}
 * @param ownerId the brand or branch
 * @param gateway gateway id, the operator's configured one
 * @param secretKey the encrypted secret API key
 * @param webhookSecret the encrypted webhook signing secret
 * @param keyHint the key's last four characters, to recognise it
 * @param status {@link #ACTIVE}, {@link #FAILED} (a call with it was refused later) or {@link #REVOKED}
 * @param current whether it is the owner's account now
 * @param lastError why it failed, or {@code null}
 * @param verifiedAt when the test call passed
 * @param connectedBy who connected it
 * @param updatedAt last change
 */
@Document("payment_merchant_accounts")
public record MerchantAccountDocument(@Id String id, String scope, String ownerId, String gateway, String secretKey,
        String webhookSecret, String keyHint, String status, boolean current, String lastError, Instant verifiedAt,
        String connectedBy, Instant updatedAt) {

    public static final String BRAND = "BRAND";

    public static final String STORE = "STORE";

    public static final String ACTIVE = "ACTIVE";

    public static final String FAILED = "FAILED";

    public static final String REVOKED = "REVOKED";
}
