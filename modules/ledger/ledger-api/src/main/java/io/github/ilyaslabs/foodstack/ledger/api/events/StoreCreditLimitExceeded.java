package io.github.ilyaslabs.foodstack.ledger.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * A branch that collects card payments itself owes the operator more than its credit limit (PRD
 * DP-7, TDD 5.4): payment stops collecting into its merchant account and notification tells the
 * branch and finance. Topic {@code foodstack.ledger.events}.
 *
 * @param storeId the branch
 * @param brandId its brand
 * @param storeName the branch's name
 * @param ownerUserIds the branch's and brand's owners, who are told
 * @param owedMinor what it owes the operator now
 * @param limitMinor its credit limit
 * @param currency ISO 4217 code
 */
public record StoreCreditLimitExceeded(String storeId, String brandId, String storeName, java.util.Set<String> ownerUserIds,
        long owedMinor, long limitMinor, String currency)
        implements ModuleEvent {

    @Override
    public String subject() {
        return this.storeId;
    }
}
