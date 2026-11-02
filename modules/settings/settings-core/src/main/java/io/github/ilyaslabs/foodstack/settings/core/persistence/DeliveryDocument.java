package io.github.ilyaslabs.foodstack.settings.core.persistence;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The {@code delivery} document of {@code settings_settings} (see {@code DeliveryView}).
 *
 * @param id always {@link #ID}
 * @param riderPayBasisPoints rider share of the operator delivery charge
 * @param riderPayMinMinor least rider pay per delivery
 * @param offerTimeoutSeconds time to accept an offer
 * @param searchRadiusMeters first search radius
 * @param searchLeadMinutes search starts this long before the food is ready
 * @param noRiderAlertSeconds support alert after this long without a rider
 * @param proofCodeAboveMinor orders from this total need a delivery code, or {@code null}
 * @param locationRetentionDays days of rider location history
 * @param updatedAt last change
 * @param updatedBy who changed it
 * @param version optimistic lock
 */
@Document("settings_settings")
public record DeliveryDocument(@Id String id, int riderPayBasisPoints, long riderPayMinMinor, int offerTimeoutSeconds,
        int searchRadiusMeters, int searchLeadMinutes, int noRiderAlertSeconds, Long proofCodeAboveMinor,
        int locationRetentionDays, Instant updatedAt, String updatedBy, @Version Long version) {

    public static final String ID = "delivery";
}
