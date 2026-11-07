package io.github.ilyaslabs.foodstack.ledger.core.application;

import java.time.Instant;

/**
 * A settled balance (PRD FI-3).
 *
 * @param id the settlement
 * @param accountId the branch's or rider's account
 * @param partyType {@code STORE} or {@code RIDER}
 * @param partyId the branch or rider
 * @param name the branch or rider name
 * @param periodTo last day settled
 * @param amountMinor what the operator paid, negative when the party paid the operator
 * @param currency ISO 4217 code
 * @param reference bank or payment reference
 * @param settledBy who settled it
 * @param settledAt when
 */
public record SettlementView(String id, String accountId, String partyType, String partyId, String name, String periodTo,
        long amountMinor, String currency, String reference, String settledBy, Instant settledAt) {
}
