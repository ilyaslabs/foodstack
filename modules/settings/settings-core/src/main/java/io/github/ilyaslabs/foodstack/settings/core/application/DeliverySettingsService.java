package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.DeliveryDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.DeliveryRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's delivery rules: rider pay (PRD DR-1c), offer timeout and search radius (DR-3),
 * support alert time (OF-8), delivery code threshold (DR-6) and location retention. Read through
 * the shared settings cache; with no document yet the defaults below apply.
 */
public class DeliverySettingsService {

    static final DeliveryDocument DEFAULTS = new DeliveryDocument(DeliveryDocument.ID, 7000, 0, 30, 5000, 10, 300, null, 30,
            null, null, null);

    private final DeliveryRepository repository;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final SettingsCache cache;

    private final Clock clock;

    public DeliverySettingsService(DeliveryRepository repository, OutboxWriter outbox, TransactionTemplate transaction,
            SettingsCache cache, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = transaction;
        this.cache = cache;
        this.clock = clock;
    }

    public DeliveryDetails get() {
        return this.cache.get(SettingsChanged.DELIVERY, DeliveryDetails.class,
                () -> details(this.repository.findById(DeliveryDocument.ID).orElse(DEFAULTS)));
    }

    public DeliveryDetails update(UpdateDelivery update, long expectedVersion, String userId) {
        validate(update);
        DeliveryDocument current = this.repository.findById(DeliveryDocument.ID).orElse(DEFAULTS);
        long currentVersion = (current.version() != null) ? current.version() : 0L;
        if (currentVersion != expectedVersion) {
            throw new ModuleException.Conflict("The delivery settings were changed by someone else; reload and retry",
                    "SETTINGS_VERSION_CONFLICT", Map.of("currentVersion", currentVersion));
        }
        DeliveryDocument changed = new DeliveryDocument(DeliveryDocument.ID, update.riderPayBasisPoints(),
                update.riderPayMinMinor(), update.offerTimeoutSeconds(), update.searchRadiusMeters(),
                update.searchLeadMinutes(), update.noRiderAlertSeconds(), update.proofCodeAboveMinor(),
                update.locationRetentionDays(), this.clock.instant(), userId, current.version());
        DeliveryDocument saved = this.transaction.execute((status) -> {
            DeliveryDocument result = this.repository.save(changed);
            this.outbox.append(new SettingsChanged(SettingsChanged.DELIVERY, result.version(), userId));
            return result;
        });
        this.cache.evict(SettingsChanged.DELIVERY);
        return details(saved);
    }

    private static DeliveryDetails details(DeliveryDocument d) {
        return new DeliveryDetails(d.riderPayBasisPoints(), d.riderPayMinMinor(), d.offerTimeoutSeconds(),
                d.searchRadiusMeters(), d.searchLeadMinutes(), d.noRiderAlertSeconds(), d.proofCodeAboveMinor(),
                d.locationRetentionDays(), (d.version() != null) ? d.version() : 0L, d.updatedAt());
    }

    private static void validate(UpdateDelivery u) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (u.riderPayBasisPoints() < 0 || u.riderPayBasisPoints() > 10000) {
            errors.put("riderPayBasisPoints", "must be between 0% and 100%");
        }
        if (u.riderPayMinMinor() < 0) {
            errors.put("riderPayMinMinor", "must be 0 or more");
        }
        if (u.offerTimeoutSeconds() < 10 || u.offerTimeoutSeconds() > 120) {
            errors.put("offerTimeoutSeconds", "must be between 10 and 120 seconds");
        }
        if (u.searchRadiusMeters() < 500 || u.searchRadiusMeters() > 30000) {
            errors.put("searchRadiusMeters", "must be between 0.5 and 30 km");
        }
        if (u.searchLeadMinutes() < 0 || u.searchLeadMinutes() > 60) {
            errors.put("searchLeadMinutes", "must be between 0 and 60 minutes");
        }
        if (u.noRiderAlertSeconds() < 60 || u.noRiderAlertSeconds() > 3600) {
            errors.put("noRiderAlertSeconds", "must be between 1 and 60 minutes");
        }
        if (u.proofCodeAboveMinor() != null && u.proofCodeAboveMinor() < 0) {
            errors.put("proofCodeAboveMinor", "must be 0 or more, or empty for never");
        }
        if (u.locationRetentionDays() < 1 || u.locationRetentionDays() > 365) {
            errors.put("locationRetentionDays", "must be between 1 and 365 days");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The settings are invalid", "SETTINGS_INVALID", Map.of("errors", errors));
        }
    }
}
