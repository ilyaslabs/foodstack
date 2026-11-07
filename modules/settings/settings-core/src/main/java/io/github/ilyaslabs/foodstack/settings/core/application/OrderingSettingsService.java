package io.github.ilyaslabs.foodstack.settings.core.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.events.SettingsChanged;
import io.github.ilyaslabs.foodstack.settings.core.persistence.OrderingDocument;
import io.github.ilyaslabs.foodstack.settings.core.persistence.OrderingRepository;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's ordering rules: payment method switches, service fee, tax, tips and how long a
 * store has to accept an order. Read through the shared settings cache; a missing document (an
 * install from before M2 that has not migrated) falls back to the defaults of the migration.
 */
public class OrderingSettingsService {

    static final int MAX_SERVICE_FEE_BASIS_POINTS = 5000;

    static final int MAX_TAX_BASIS_POINTS = 10000;

    static final int MIN_ACCEPT_SECONDS = 60;

    static final int MAX_ACCEPT_SECONDS = 1800;

    private static final OrderingDocument DEFAULTS = new OrderingDocument(OrderingDocument.ID, true, false, 0, 0, 0, true,
            180, null, null, null, false, false, null);

    private final OrderingRepository repository;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final SettingsCache cache;

    private final Clock clock;

    public OrderingSettingsService(OrderingRepository repository, OutboxWriter outbox, TransactionTemplate transaction,
            SettingsCache cache, Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = transaction;
        this.cache = cache;
        this.clock = clock;
    }

    public OrderingDetails get() {
        return this.cache.get(SettingsChanged.ORDERING, OrderingDetails.class,
                () -> details(this.repository.findById(OrderingDocument.ID).orElse(DEFAULTS)));
    }

    public OrderingDetails update(UpdateOrdering update, long expectedVersion, String userId) {
        validate(update);
        OrderingDocument current = this.repository.findById(OrderingDocument.ID).orElse(DEFAULTS);
        long currentVersion = (current.version() != null) ? current.version() : 0L;
        if (currentVersion != expectedVersion) {
            throw new ModuleException.Conflict("The ordering settings were changed by someone else; reload and retry",
                    "SETTINGS_VERSION_CONFLICT", Map.of("currentVersion", currentVersion));
        }
        OrderingDocument changed = new OrderingDocument(OrderingDocument.ID, update.cashEnabled(), update.cardEnabled(),
                update.serviceFeeMinor(), update.serviceFeeBasisPoints(), update.taxBasisPoints(), update.tipsEnabled(),
                update.acceptTimeoutSeconds(), this.clock.instant(), userId, current.version(), update.priceApprovalRequired(),
                update.directCollectionEnabled(), update.creditLimitMinor());
        OrderingDocument saved = this.transaction.execute((status) -> {
            OrderingDocument result = this.repository.save(changed);
            this.outbox.append(new SettingsChanged(SettingsChanged.ORDERING, result.version(), userId));
            return result;
        });
        this.cache.evict(SettingsChanged.ORDERING);
        return details(saved);
    }

    private static OrderingDetails details(OrderingDocument d) {
        return new OrderingDetails(d.cashEnabled(), d.cardEnabled(), d.serviceFeeMinor(), d.serviceFeeBasisPoints(),
                d.taxBasisPoints(), d.tipsEnabled(), d.acceptTimeoutSeconds(), (d.version() != null) ? d.version() : 0L,
                d.updatedAt(), d.approvalRequired(), d.directCollection(), d.creditLimit());
    }

    private static void validate(UpdateOrdering update) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (!update.cashEnabled() && !update.cardEnabled()) {
            errors.put("cashEnabled", "keep at least one payment method on");
        }
        if (update.creditLimitMinor() < 0) {
            errors.put("creditLimitMinor", "must be 0 or more");
        }
        if (update.serviceFeeMinor() < 0) {
            errors.put("serviceFeeMinor", "must be 0 or more");
        }
        if (update.serviceFeeBasisPoints() < 0 || update.serviceFeeBasisPoints() > MAX_SERVICE_FEE_BASIS_POINTS) {
            errors.put("serviceFeeBasisPoints", "must be between 0% and 50%");
        }
        if (update.taxBasisPoints() < 0 || update.taxBasisPoints() > MAX_TAX_BASIS_POINTS) {
            errors.put("taxBasisPoints", "must be between 0% and 100%");
        }
        if (update.acceptTimeoutSeconds() < MIN_ACCEPT_SECONDS || update.acceptTimeoutSeconds() > MAX_ACCEPT_SECONDS) {
            errors.put("acceptTimeoutSeconds", "must be between 1 and 30 minutes");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The settings are invalid", "SETTINGS_INVALID", Map.of("errors", errors));
        }
    }
}
