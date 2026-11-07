package io.github.ilyaslabs.foodstack.store.api.events;

import io.github.ilyaslabs.foodstack.platform.core.ModuleEvent;

/**
 * Finance allowed or stopped a branch collecting card payments itself, or changed its credit limit
 * (PRD DP-2, DP-7, TDD 5.4). Payment and the ledger keep their own copy. Topic
 * {@code foodstack.store.events}, type {@code foodstack.store.StorePaymentCollectionChanged.v1}.
 *
 * @param storeId the branch
 * @param brandId its brand, whose merchant account it may use
 * @param storeName the branch's name, for messages
 * @param ownerUserIds the branch's and its brand's owners, told when it goes over its credit limit
 * @param mode {@code OPERATOR} or {@code DIRECT}
 * @param creditLimitMinor the branch's own credit limit, or {@code null} for the operator's default
 * @param reason why
 * @param changedBy who
 */
public record StorePaymentCollectionChanged(String storeId, String brandId, String storeName, java.util.Set<String> ownerUserIds,
        String mode, Long creditLimitMinor, String reason, String changedBy) implements ModuleEvent {

    public static final String OPERATOR = "OPERATOR";

    public static final String DIRECT = "DIRECT";

    @Override
    public String subject() {
        return this.storeId;
    }
}
