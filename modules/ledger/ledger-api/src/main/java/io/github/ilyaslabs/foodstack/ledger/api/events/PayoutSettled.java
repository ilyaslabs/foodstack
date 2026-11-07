package io.github.ilyaslabs.foodstack.ledger.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Finance settled a branch's or a rider's balance for a period (PRD FI-3). Topic
 * {@code foodstack.ledger.events}.
 *
 * @param settlementId the settlement
 * @param accountId the ledger account, such as {@code store:<id>} or {@code rider:<id>}
 * @param partyType {@code STORE} or {@code RIDER}
 * @param partyId the branch or the rider
 * @param amountMinor what the operator paid the party, negative when the party paid the operator
 * @param currency ISO 4217 code
 * @param periodTo last day settled, ISO date
 * @param reference the bank or payment reference finance typed
 * @param actor who settled it
 */
public record PayoutSettled(String settlementId, String accountId, String partyType, String partyId, long amountMinor,
        String currency, String periodTo, String reference, String actor) implements ModuleEvent {

    @Override
    public String subject() {
        return this.partyId;
    }
}
