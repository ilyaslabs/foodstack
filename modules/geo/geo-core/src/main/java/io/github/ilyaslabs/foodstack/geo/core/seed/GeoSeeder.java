package io.github.ilyaslabs.foodstack.geo.core.seed;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.github.ilyaslabs.foodstack.geo.api.GeoPoint;
import io.github.ilyaslabs.foodstack.geo.core.application.CityInput;
import io.github.ilyaslabs.foodstack.geo.core.application.CityPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.CityService;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricingService;
import io.github.ilyaslabs.foodstack.geo.core.application.UpdateOperatorPricing;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneInput;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneService;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.bson.Document;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

/**
 * Delivery zones for the demo restaurants and operator delivery pricing, for the {@code test-seed}
 * profile (TDD 16). The branch ids match the store module's demo data; writes only geo data, once.
 */
@Component
@Profile("test-seed")
class GeoSeeder {

    private static final Log logger = LogFactory.getLog(GeoSeeder.class);

    private static final String SPICE_ROUTE_GULBERG = "0195f0a0-0000-7000-8000-000000000101";

    private static final String NAPOLI_DHA = "0195f0a0-0000-7000-8000-000000000201";

    private final ZoneService zones;

    private final OperatorPricingService pricing;

    private final MongoTemplate mongo;

    private final CityService cities;

    GeoSeeder(ZoneService zones, OperatorPricingService pricing, MongoTemplate geoMongoTemplate, CityService geoCityService) {
        this.cities = geoCityService;
        this.zones = zones;
        this.pricing = pricing;
        this.mongo = geoMongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(25)
    void seed() {
        try {
            this.mongo.insert(new Document("_id", "v1").append("seededAt", Instant.now()), "geo_seed");
        }
        catch (DuplicateKeyException ex) {
            return;
        }
        rings(SPICE_ROUTE_GULBERG, new GeoPoint(31.5120, 74.3440));
        rings(NAPOLI_DHA, new GeoPoint(31.4630, 74.4080));
        this.pricing.update(new UpdateOperatorPricing(true, 15_000, 3_000, 10_000), this.pricing.get().version(), "seed");
        // The demo branches are in Lahore, which uses the operator-wide pricing; Islamabad shows a city's own prices
        this.cities.create(new CityInput(Map.of("en", "Lahore", "ar", "لاهور"), new GeoPoint(31.5204, 74.3587), true, null),
                "seed");
        this.cities.create(new CityInput(Map.of("en", "Islamabad", "ar", "إسلام آباد"), new GeoPoint(33.6844, 73.0479), true,
                new CityPricing(12_000, 2_500, 12_000, 0, null, 100, List.of())), "seed");
        logger.info("Seeded delivery zones for the demo restaurants, operator delivery pricing and the cities");
    }

    private void rings(String storeId, GeoPoint center) {
        this.zones.replaceZones(storeId, List.of(new ZoneInput("Up to 3 km", center, 3000, null, 10_000, 50_000, 300_000L),
                new ZoneInput("3 to 7 km", center, 7000, null, 20_000, 80_000, null)), "seed");
    }
}
