package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * Branch details only the operator edits.
 *
 * @param legalName registered business name
 * @param taxId tax id, may be {@code null}
 * @param operatorDeliveryAllowed whether the branch may choose operator delivery (PRD ST-13)
 */
public record StoreAdminUpdate(String legalName, String taxId, boolean operatorDeliveryAllowed) {
}
