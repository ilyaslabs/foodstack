package io.github.ilyaslabs.foodstack.geo.core.application;

import java.text.Collator;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.api.events.CityChanged;
import io.github.ilyaslabs.foodstack.geo.core.domain.GeoMath;
import io.github.ilyaslabs.foodstack.geo.core.persistence.CityDocument;
import io.github.ilyaslabs.foodstack.geo.core.persistence.CityRepository;
import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.settings.api.LocaleView;
import io.github.ilyaslabs.moduleswitch.ModuleException;

import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cities the operator works in (PRD DR-1, DR-1b, PR-2). A branch and an operator rider each
 * belong to one city; dispatch only offers a branch's jobs to riders of its city, and a city may
 * have its own operator delivery pricing. A city is never deleted, only switched off, because
 * branches, riders and coupons keep pointing at it.
 */
public class CityService {

    static final int MAX_CITIES = 500;

    private final CityRepository repository;

    private final OutboxWriter outbox;

    private final TransactionTemplate transaction;

    private final Clock clock;

    private final GeoCache cache;

    private final Supplier<LocaleView> locale;

    private final int nearestRadiusMeters;

    public CityService(CityRepository repository, OutboxWriter outbox, TransactionTemplate transaction, Clock clock,
            GeoCache cache, Supplier<LocaleView> locale, int nearestRadiusMeters) {
        this.repository = repository;
        this.outbox = outbox;
        this.transaction = transaction;
        this.clock = clock;
        this.cache = cache;
        this.locale = locale;
        this.nearestRadiusMeters = nearestRadiusMeters;
    }

    /** Every city, sorted by its name in the default language. */
    public List<City> list() {
        return this.cache.cities(() -> {
            LocaleView view = this.locale.get();
            String language = view.defaultLanguage();
            Collator collator = Collator.getInstance(Locale.forLanguageTag(language));
            return this.repository.findAll()
                .stream()
                .map(CityService::toCity)
                .sorted(Comparator.comparing((City c) -> c.name().getOrDefault(language, ""), collator))
                .toList();
        });
    }

    public City get(String cityId) {
        return list().stream()
            .filter((c) -> c.id().equals(cityId))
            .findFirst()
            .orElseThrow(() -> new ModuleException.NotFound("City " + cityId + " not found", "CITY_NOT_FOUND"));
    }

    /** The city's own pricing, or {@code null} when it has none, is unknown or is switched off. */
    public CityPricing pricingOf(String cityId) {
        if (cityId == null) {
            return null;
        }
        return list().stream()
            .filter((c) -> c.id().equals(cityId) && c.active())
            .map(City::pricing)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }

    /** The active city nearest to a point within the radius, or {@code null}. */
    public City nearest(GeoPoint point) {
        if (point == null || !point.isValid()) {
            return null;
        }
        City best = null;
        double bestDistance = this.nearestRadiusMeters;
        for (City city : list()) {
            if (!city.active()) {
                continue;
            }
            double distance = GeoMath.distanceMeters(point, city.centre());
            if (distance <= bestDistance) {
                best = city;
                bestDistance = distance;
            }
        }
        return best;
    }

    public City create(CityInput input, String userId) {
        validate(input, null);
        if (this.repository.count() >= MAX_CITIES) {
            throw new ModuleException.Conflict("A deployment can have at most " + MAX_CITIES + " cities", "CITY_LIMIT");
        }
        CityDocument city = new CityDocument(Ids.newId(), cleanName(input.name()), input.centre().lat(),
                input.centre().lng(), input.active(), pricing(input.pricing()), this.clock.instant(), userId, null);
        return save(city, userId);
    }

    public City update(String cityId, CityInput input, long expectedVersion, String userId) {
        validate(input, cityId);
        CityDocument current = this.repository.findById(cityId)
            .orElseThrow(() -> new ModuleException.NotFound("City " + cityId + " not found", "CITY_NOT_FOUND"));
        long version = (current.version() != null) ? current.version() : 0;
        if (version != expectedVersion) {
            throw new ModuleException.Conflict("The city was changed by someone else; reload and retry",
                    "CITY_VERSION_CONFLICT", Map.of("currentVersion", version));
        }
        CityDocument changed = new CityDocument(cityId, cleanName(input.name()), input.centre().lat(), input.centre().lng(),
                input.active(), pricing(input.pricing()), this.clock.instant(), userId, current.version());
        return save(changed, userId);
    }

    private City save(CityDocument city, String userId) {
        CityDocument saved = this.transaction.execute((status) -> {
            CityDocument result = this.repository.save(city);
            this.outbox.append(new CityChanged(result.id(), result.version(), userId));
            return result;
        });
        this.cache.evictCities();
        return toCity(saved);
    }

    private void validate(CityInput input, String cityId) {
        LocaleView view = this.locale.get();
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> name = (input.name() != null) ? input.name() : Map.of();
        String main = name.get(view.defaultLanguage());
        if (main == null || main.isBlank() || main.strip().length() > 60) {
            errors.put("name." + view.defaultLanguage(), "must be 1 to 60 characters");
        }
        name.forEach((language, value) -> {
            if (!view.languages().contains(language)) {
                errors.put("name." + language, "is not a language of this deployment");
            }
            else if (value != null && value.strip().length() > 60) {
                errors.put("name." + language, "must be at most 60 characters");
            }
        });
        if (input.centre() == null || !input.centre().isValid()) {
            errors.put("centre", "must be a valid point");
        }
        if (main != null && !main.isBlank()) {
            String key = main.strip().toLowerCase(Locale.ROOT);
            boolean taken = this.repository.findAll()
                .stream()
                .anyMatch((c) -> !c.id().equals(cityId)
                        && key.equals(c.name().getOrDefault(view.defaultLanguage(), "").toLowerCase(Locale.ROOT)));
            if (taken) {
                errors.put("name." + view.defaultLanguage(), "another city has this name");
            }
        }
        if (!errors.isEmpty()) {
            throw new ModuleException.BadRequest("The city is invalid", "CITY_INVALID", Map.of("errors", errors));
        }
        CityPricing pricing = input.pricing();
        if (pricing != null) {
            OperatorPricingService.validate(new UpdateOperatorPricing(true, pricing.baseChargeMinor(), pricing.perKmMinor(),
                    pricing.maxDistanceMeters(), pricing.minOrderMinor(), pricing.freeDeliveryAboveMinor(),
                    pricing.peakMultiplierPercent(), pricing.peakWindows()));
        }
    }

    private static Map<String, String> cleanName(Map<String, String> name) {
        Map<String, String> clean = new LinkedHashMap<>();
        name.forEach((language, value) -> {
            if (value != null && !value.isBlank()) {
                clean.put(language, value.strip());
            }
        });
        return clean;
    }

    private static CityDocument.Pricing pricing(CityPricing pricing) {
        return (pricing != null) ? new CityDocument.Pricing(pricing.baseChargeMinor(), pricing.perKmMinor(),
                pricing.maxDistanceMeters(), pricing.minOrderMinor(), pricing.freeDeliveryAboveMinor(),
                pricing.peakMultiplierPercent(), pricing.peakWindows()) : null;
    }

    private static City toCity(CityDocument document) {
        CityDocument.Pricing p = document.pricing();
        CityPricing pricing = (p != null) ? new CityPricing(p.baseChargeMinor(), p.perKmMinor(), p.maxDistanceMeters(),
                p.minOrderMinor(), p.freeDeliveryAboveMinor(), p.peakMultiplierPercent(), p.peakWindows()) : null;
        return new City(document.id(), Map.copyOf(document.name()), new GeoPoint(document.centreLat(), document.centreLng()),
                document.active(), pricing, (document.version() != null) ? document.version() : 0, document.updatedAt());
    }
}
