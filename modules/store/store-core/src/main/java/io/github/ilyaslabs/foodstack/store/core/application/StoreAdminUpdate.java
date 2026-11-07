package io.github.ilyaslabs.foodstack.store.core.application;

/**
 * Branch details only the operator edits.
 *
 * @param legalName registered business name
 * @param taxId tax id, may be {@code null}
 * @param operatorDeliveryAllowed whether the branch may choose operator delivery (PRD ST-13)
 * @param cityId the operator's city; {@code null} keeps the current one (or picks the nearest)
 */
public record StoreAdminUpdate(String legalName, String taxId, boolean operatorDeliveryAllowed, String cityId) {

    public StoreAdminUpdate(String legalName, String taxId, boolean operatorDeliveryAllowed) {
        this(legalName, taxId, operatorDeliveryAllowed, null);
    }
}
