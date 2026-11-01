package io.github.ilyaslabs.foodstack.geo.core.persistence;

import java.time.Instant;
import java.util.List;

import io.github.ilyaslabs.foodstack.platform.core.Ids;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.geo.Point;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.geo.GeoJsonPolygon;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = GeoRepositoriesTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class GeoRepositoriesTest {

    /** The persistence part of GeoConfiguration, without Kafka and Redis. */
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMongoRepositories(basePackageClasses = ZoneRepository.class, mongoTemplateRef = "geoMongoTemplate")
    static class App {

        @Bean
        ModuleMongo geoMongo(ModuleMongoFactory factory) {
            return factory.create("geo");
        }

        @Bean
        MongoTemplate geoMongoTemplate(ModuleMongo geoMongo) {
            return geoMongo.template();
        }

        @Bean
        ModuleMigrations geoMigrations(ModuleMongo geoMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
            return new ModuleMigrations(geoMongo, factory.connectionString("geo"), ShardKeys.none(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ZoneRepository zones;

    @Autowired
    OperatorPricingRepository pricing;

    @Autowired
    MongoTemplate geoMongoTemplate;

    @Test
    @DisplayName("keeps a branch's zones in order and finds the zone that contains a point with the 2dsphere index")
    void zones() {
        // given
        String storeId = Ids.newId();
        this.zones.saveAll(List.of(zone(storeId, 1, 74.30, 31.50, 74.40, 31.56), zone(storeId, 0, 74.33, 31.51, 74.37, 31.53)));

        // when
        List<ZoneDocument> found = this.zones.findByStoreIdOrderBySortOrderAsc(storeId);
        List<ZoneDocument> containing = this.geoMongoTemplate.find(Query.query(Criteria.where("storeId").is(storeId)
            .and("area").intersects(new GeoJsonPoint(74.35, 31.52))), ZoneDocument.class);

        // then
        assertThat(found).extracting(ZoneDocument::getSortOrder).containsExactly(0, 1);
        assertThat(containing).hasSize(2);
        assertThat(this.zones.deleteByStoreId(storeId)).isEqualTo(2);
        assertThat(this.geoMongoTemplate.getDb().getName()).endsWith("_geo");
    }

    @Test
    @DisplayName("seeds operator delivery pricing switched off")
    void seededPricing() {
        OperatorPricingDocument pricing = this.pricing.findById(OperatorPricingDocument.ID).orElseThrow();

        assertThat(pricing.isEnabled()).isFalse();
        assertThat(pricing.getMaxDistanceMeters()).isEqualTo(8000);
        assertThat(pricing.getVersion()).isZero();
    }

    private static ZoneDocument zone(String storeId, int order, double minLng, double minLat, double maxLng, double maxLat) {
        ZoneDocument zone = new ZoneDocument();
        zone.setId(Ids.newId());
        zone.setStoreId(storeId);
        zone.setName("Zone " + order);
        zone.setSortOrder(order);
        zone.setArea(new GeoJsonPolygon(new Point(minLng, minLat), new Point(maxLng, minLat), new Point(maxLng, maxLat),
                new Point(minLng, maxLat), new Point(minLng, minLat)));
        zone.setChargeMinor(10_000);
        zone.setUpdatedAt(Instant.now());
        return zone;
    }
}
