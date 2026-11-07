package io.github.ilyaslabs.foodstack.settings.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The {@code ordering} document of {@code settings_settings}.
 *
 * @param id always {@link #ID}
 * @param cashEnabled cash payments allowed
 * @param cardEnabled card payments allowed
 * @param serviceFeeMinor fixed service fee
 * @param serviceFeeBasisPoints service fee share of the food subtotal
 * @param taxBasisPoints tax on the food subtotal after discounts
 * @param tipsEnabled tips allowed
 * @param acceptTimeoutSeconds store accept time
 * @param updatedAt last change
 * @param updatedBy who changed it
 * @param version optimistic lock
 * @param priceApprovalRequired price changes wait for approval ({@code null} before M4: off)
 * @param directCollectionEnabled branches may collect card payments themselves ({@code null}: off)
 * @param creditLimitMinor default credit limit of a direct-collecting branch ({@code null}: the default)
 */
@Document("settings_settings")
public record OrderingDocument(@Id String id, boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor,
        int serviceFeeBasisPoints, int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, Instant updatedAt,
        String updatedBy, @Version Long version, Boolean priceApprovalRequired, Boolean directCollectionEnabled,
        Long creditLimitMinor) {

    /** Off unless set (PRD DP-1). */
    public boolean directCollection() {
        return Boolean.TRUE.equals(this.directCollectionEnabled);
    }

    /** The default credit limit, 50,000 in a two-decimal currency unless set. */
    public long creditLimit() {
        return (this.creditLimitMinor != null) ? this.creditLimitMinor : 5_000_000L;
    }

    /** Off unless set: documents saved before M4 have no value. */
    public boolean approvalRequired() {
        return Boolean.TRUE.equals(this.priceApprovalRequired);
    }

    public static final String ID = "ordering";
}
