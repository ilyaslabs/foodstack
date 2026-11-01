package io.github.ilyaslabs.foodstack.store.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.foodstack.store.core.application.MenuCache;
import io.github.ilyaslabs.foodstack.store.core.application.MenuService;
import io.github.ilyaslabs.foodstack.store.core.application.OrderValidationService;
import io.github.ilyaslabs.foodstack.store.core.application.SlugBackfill;
import io.github.ilyaslabs.foodstack.store.core.application.StoreListingService;
import io.github.ilyaslabs.foodstack.store.core.application.StoreService;
import io.github.ilyaslabs.foodstack.store.core.persistence.BrandRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.ItemOverrideRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuItemRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.MenuSectionRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreRepository;
import io.github.ilyaslabs.foodstack.store.core.persistence.StoreSlugRepository;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Everything the store module runs on: database {@code foodstack_store} with its shard keys,
 * migrations, outbox and relay, the menu cache. Lives in the module's package, so it disappears
 * when store is not local.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = StoreRepository.class, mongoTemplateRef = "storeMongoTemplate")
public class StoreConfiguration {

    public static final String MODULE = "store";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("store_brands", "_id")
            .hashed("store_stores", "_id")
            .hashed("store_slugs", "_id")
            .hashed("store_menu_sections", "brandId")
            .hashed("store_menu_items", "brandId")
            .hashed("store_item_overrides", "storeId");
    }

    @Bean
    ModuleMongo storeMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate storeMongoTemplate(ModuleMongo storeMongo) {
        return storeMongo.template();
    }

    @Bean
    MongoTransactionManager storeTransactionManager(ModuleMongo storeMongo) {
        return storeMongo.transactionManager();
    }

    @Bean
    ModuleMigrations storeMigrations(ModuleMongo storeMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(storeMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter storeOutbox(ModuleEventing eventing, ModuleMongo storeMongo) {
        return eventing.outbox(storeMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay storeOutboxRelay(ModuleEventing eventing, ModuleMongo storeMongo) {
        return eventing.relay(storeMongo);
    }

    @Bean
    KafkaAdmin.NewTopics storeTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    MenuCache storeMenuCache(StringRedisTemplate redis, JsonMapper jsonMapper,
            @Value("${foodstack.namespace:foodstack}") String namespace,
            @Value("${foodstack.store.menu-cache-ttl:10m}") Duration ttl) {
        return new MenuCache(redis, jsonMapper, namespace, ttl);
    }

    @Bean
    StoreService storeService(BrandRepository brandRepository, StoreRepository storeRepository,
            StoreSlugRepository storeSlugRepository, MenuItemRepository menuItemRepository, SettingsApi settingsApi, MediaApi mediaApi, IdentityApi identityApi,
            GeoApi geoApi, OutboxWriter storeOutbox, ModuleMongo storeMongo, Clock clock) {
        return new StoreService(brandRepository, storeRepository, storeSlugRepository, menuItemRepository, settingsApi, mediaApi, identityApi,
                geoApi, storeOutbox, storeMongo.transactionTemplate(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    SlugBackfill storeSlugBackfill(StoreService storeService) {
        return new SlugBackfill(storeService);
    }

    @Bean
    MenuService storeMenuService(MenuSectionRepository menuSectionRepository, MenuItemRepository menuItemRepository,
            ItemOverrideRepository itemOverrideRepository, StoreRepository storeRepository, BrandRepository brandRepository,
            SettingsApi settingsApi, MediaApi mediaApi, MenuCache storeMenuCache, OutboxWriter storeOutbox,
            ModuleMongo storeMongo, Clock clock) {
        return new MenuService(menuSectionRepository, menuItemRepository, itemOverrideRepository, storeRepository,
                brandRepository, settingsApi, mediaApi, storeMenuCache, storeOutbox, storeMongo.transactionTemplate(), clock);
    }

    @Bean
    StoreListingService storeListingService(StoreService storeService, MenuService storeMenuService,
            StoreRepository storeRepository, SettingsApi settingsApi) {
        return new StoreListingService(storeService, storeMenuService, storeRepository, settingsApi);
    }

    @Bean
    OrderValidationService storeOrderValidationService(StoreService storeService, MenuService storeMenuService,
            SettingsApi settingsApi) {
        return new OrderValidationService(storeService, storeMenuService, settingsApi);
    }
}
