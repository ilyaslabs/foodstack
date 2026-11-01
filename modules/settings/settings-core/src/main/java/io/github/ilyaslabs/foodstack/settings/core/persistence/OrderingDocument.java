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
 */
@Document("settings_settings")
public record OrderingDocument(@Id String id, boolean cashEnabled, boolean cardEnabled, long serviceFeeMinor,
        int serviceFeeBasisPoints, int taxBasisPoints, boolean tipsEnabled, int acceptTimeoutSeconds, Instant updatedAt,
        String updatedBy, @Version Long version) {

    public static final String ID = "ordering";
}
