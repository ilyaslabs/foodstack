package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.events.OperatorDeliveryPricingChanged;
import io.github.ilyaslabs.foodstack.geo.core.persistence.OperatorPricingDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.OperatorPricingRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's delivery pricing (PRD DR-1b): what customers pay for operator-delivery branches,
 * so the operator never runs short on rider pay.
 */
public class OperatorPricingService {

    private final OperatorPricingRepository repository;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    public OperatorPricingService(OperatorPricingRepository repository, OutboxWriter outbox, TransactionTemplate transaction,
            Clock clock) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
    }

    public OperatorPricing get() {
        return toDetails(load());
    }

    public OperatorPricing update(UpdateOperatorPricing update, long expectedVersion, String userId) {
        validate(update);
        OperatorPricingDocument pricing = load();
        long current = (pricing.getVersion() != null) ? pricing.getVersion() : 0;
        if (current != expectedVersion) {
            throw new ModuleException.Conflict("The delivery pricing was changed by someone else; reload and retry",
                    "PRICING_VERSION_CONFLICT", Map.of("currentVersion", current));
        }
        pricing.setEnabled(update.enabled());
        pricing.setBaseChargeMinor(update.baseChargeMinor());
        pricing.setPerKmMinor(update.perKmMinor());
        pricing.setMaxDistanceMeters(update.maxDistanceMeters());
        pricing.setUpdatedAt(this.clock.instant());
        pricing.setUpdatedBy(userId);
        OperatorPricingDocument saved = this.transaction.execute((status) -> {
            OperatorPricingDocument result = this.repository.save(pricing);
            this.outbox.append(new OperatorDeliveryPricingChanged(result.getVersion(), userId));
            return result;
        });
        return toDetails(saved);
    }

    private OperatorPricingDocument load() {
        return this.repository.findById(OperatorPricingDocument.ID)
            .orElseThrow(() -> new ModuleException.Internal("Operator delivery pricing is missing; run the migrations",
                    "PRICING_MISSING"));
    }

    private static OperatorPricing toDetails(OperatorPricingDocument document) {
        return new OperatorPricing(document.isEnabled(), document.getBaseChargeMinor(), document.getPerKmMinor(),
                document.getMaxDistanceMeters(), (document.getVersion() != null) ? document.getVersion() : 0,
                document.getUpdatedAt());
    }

    private static void validate(UpdateOperatorPricing update) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (update.baseChargeMinor() < 0) {
            errors.put("baseChargeMinor", "must not be negative");
        }
        if (update.perKmMinor() < 0) {
            errors.put("perKmMinor", "must not be negative");
        }
        if (update.maxDistanceMeters() < 1000 || update.maxDistanceMeters() > 50_000) {
            errors.put("maxDistanceMeters", "must be 1 to 50 km");
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The delivery pricing is invalid", "PRICING_INVALID",
                    Map.of("errors", errors));
        }
    }
}
