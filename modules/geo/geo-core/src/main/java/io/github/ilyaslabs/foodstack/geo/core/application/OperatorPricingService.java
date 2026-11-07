package io.github.ilyaslabs.foodstack.geo.core.application;

import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
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

    static final int MAX_PEAK_WINDOWS = 10;

    private final OperatorPricingRepository repository;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    private final GeoCache cache;

    public OperatorPricingService(OperatorPricingRepository repository, OutboxWriter outbox, TransactionTemplate transaction,
            Clock clock) {
        this(repository, outbox, transaction, clock, GeoCache.none());
    }

    public OperatorPricingService(OperatorPricingRepository repository, OutboxWriter outbox, TransactionTemplate transaction,
            Clock clock, GeoCache cache) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
        this.cache = cache;
    }

    /** The pricing, from the cache when there; changes always check the stored version. */
    public OperatorPricing get() {
        return this.cache.operatorPricing(() -> toDetails(load()));
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
        pricing.setMinOrderMinor(update.minOrderMinor());
        pricing.setFreeDeliveryAboveMinor(update.freeDeliveryAboveMinor());
        pricing.setPeakMultiplierPercent(update.peakMultiplierPercent());
        pricing.setPeakWindows(update.peakWindows());
        pricing.setUpdatedAt(this.clock.instant());
        pricing.setUpdatedBy(userId);
        OperatorPricingDocument saved = this.transaction.execute((status) -> {
            OperatorPricingDocument result = this.repository.save(pricing);
            this.outbox.append(new OperatorDeliveryPricingChanged(result.getVersion(), userId));
            return result;
        });
        this.cache.evictOperatorPricing();
        return toDetails(saved);
    }

    private static LocalTime time(String value) {
        if (value == null || !value.matches("\\d{2}:\\d{2}")) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        }
        catch (DateTimeParseException ex) {
            return null;
        }
    }

    private OperatorPricingDocument load() {
        return this.repository.findById(OperatorPricingDocument.ID)
            .orElseThrow(() -> new ModuleException.Internal("Operator delivery pricing is missing; run the migrations",
                    "PRICING_MISSING"));
    }

    private static OperatorPricing toDetails(OperatorPricingDocument document) {
        int peak = (document.getPeakMultiplierPercent() > 0) ? document.getPeakMultiplierPercent() : 100;
        return new OperatorPricing(document.isEnabled(), document.getBaseChargeMinor(), document.getPerKmMinor(),
                document.getMaxDistanceMeters(), document.getMinOrderMinor(), document.getFreeDeliveryAboveMinor(), peak,
                document.getPeakWindows(), (document.getVersion() != null) ? document.getVersion() : 0,
                document.getUpdatedAt());
    }

    static void validate(UpdateOperatorPricing update) {
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
        if (update.minOrderMinor() < 0) {
            errors.put("minOrderMinor", "must not be negative");
        }
        if (update.freeDeliveryAboveMinor() != null && update.freeDeliveryAboveMinor() <= 0) {
            errors.put("freeDeliveryAboveMinor", "must be more than 0, or empty for never free");
        }
        if (update.peakMultiplierPercent() < 100 || update.peakMultiplierPercent() > 300) {
            errors.put("peakMultiplierPercent", "must be 100 to 300 (x1 to x3)");
        }
        if (update.peakWindows().size() > MAX_PEAK_WINDOWS) {
            errors.put("peakWindows", "at most " + MAX_PEAK_WINDOWS + " windows");
        }
        for (int i = 0; i < update.peakWindows().size(); i++) {
            PeakWindow window = update.peakWindows().get(i);
            LocalTime start = time(window.start());
            LocalTime end = time(window.end());
            if (start == null || end == null || start.equals(end)) {
                errors.put("peakWindows[" + i + "]", "needs a start and a different end like 19:00 and 22:00");
            }
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The delivery pricing is invalid", "PRICING_INVALID",
                    Map.of("errors", errors));
        }
    }
}
