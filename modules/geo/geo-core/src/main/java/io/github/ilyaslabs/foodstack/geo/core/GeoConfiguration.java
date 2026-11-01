package io.github.ilyaslabs.foodstack.geo.core;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.geo.core.application.GeocodingService;
import io.github.ilyaslabs.foodstack.geo.core.application.OperatorPricingService;
import io.github.ilyaslabs.foodstack.geo.core.application.QuoteService;
import io.github.ilyaslabs.foodstack.geo.core.application.ZoneService;
import io.github.ilyaslabs.foodstack.geo.core.geocoding.DisabledGeocodingProvider;
import io.github.ilyaslabs.foodstack.geo.core.geocoding.GeocodingProvider;
import io.github.ilyaslabs.foodstack.geo.core.geocoding.NominatimGeocodingProvider;
import io.github.ilyaslabs.foodstack.geo.core.persistence.OperatorPricingRepository;
import io.github.ilyaslabs.foodstack.geo.core.persistence.ZoneRepository;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.client.RestClient;

/**
 * Everything the geo module runs on: database {@code foodstack_geo}, migrations, outbox and relay,
 * the address search provider. Lives in the module's package, so it disappears when geo is not local.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeoProperties.class)
@EnableMongoRepositories(basePackageClasses = ZoneRepository.class, mongoTemplateRef = "geoMongoTemplate")
public class GeoConfiguration {

    public static final String MODULE = "geo";

    @Bean
    ModuleMongo geoMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate geoMongoTemplate(ModuleMongo geoMongo) {
        return geoMongo.template();
    }

    @Bean
    MongoTransactionManager geoTransactionManager(ModuleMongo geoMongo) {
        return geoMongo.transactionManager();
    }

    @Bean
    ModuleMigrations geoMigrations(ModuleMongo geoMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(geoMongo, factory.connectionString(MODULE), ShardKeys.none(), properties);
    }

    @Bean
    OutboxWriter geoOutbox(ModuleEventing eventing, ModuleMongo geoMongo) {
        return eventing.outbox(geoMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay geoOutboxRelay(ModuleEventing eventing, ModuleMongo geoMongo) {
        return eventing.relay(geoMongo);
    }

    @Bean
    KafkaAdmin.NewTopics geoTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    ZoneService geoZoneService(ZoneRepository zoneRepository, OutboxWriter geoOutbox, ModuleMongo geoMongo, Clock clock) {
        return new ZoneService(zoneRepository, geoOutbox, geoMongo.transactionTemplate(), clock);
    }

    @Bean
    OperatorPricingService geoOperatorPricingService(OperatorPricingRepository operatorPricingRepository,
            OutboxWriter geoOutbox, ModuleMongo geoMongo, Clock clock) {
        return new OperatorPricingService(operatorPricingRepository, geoOutbox, geoMongo.transactionTemplate(), clock);
    }

    @Bean
    QuoteService geoQuoteService(ZoneService geoZoneService, OperatorPricingService geoOperatorPricingService,
            GeoProperties properties) {
        return new QuoteService(geoZoneService, geoOperatorPricingService, properties.getRoadFactor());
    }

    @Bean
    GeocodingProvider geoGeocodingProvider(GeoProperties properties) {
        GeoProperties.Geocoding geocoding = properties.getGeocoding();
        if (!"nominatim".equalsIgnoreCase(geocoding.getProvider())) {
            return new DisabledGeocodingProvider();
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(geocoding.getTimeout());
        requestFactory.setReadTimeout(geocoding.getTimeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        return new NominatimGeocodingProvider(builder, geocoding.getUrl(), geocoding.getUserAgent(),
                geocoding.getCountryCodes());
    }

    @Bean
    GeocodingService geoGeocodingService(GeocodingProvider geoGeocodingProvider, StringRedisTemplate redis,
            JsonMapper jsonMapper, @Value("${foodstack.namespace:foodstack}") String namespace, GeoProperties properties) {
        return new GeocodingService(geoGeocodingProvider, redis, jsonMapper, namespace,
                properties.getGeocoding().getCacheTtl());
    }
}
