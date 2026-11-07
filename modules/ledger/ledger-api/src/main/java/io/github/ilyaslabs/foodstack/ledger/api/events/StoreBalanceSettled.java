package io.github.ilyaslabs.foodstack.ledger.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A branch's balance was settled (TDD 5.4); a merchant account suspended for going over the credit
 * limit may be turned back on. Topic {@code foodstack.ledger.events}.
 *
 * @param storeId the branch
 * @param balanceMinor the branch's balance after the settlement
 * @param currency ISO 4217 code
 */
public record StoreBalanceSettled(String storeId, long balanceMinor, String currency) implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
