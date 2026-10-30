package io.github.ilyaslabs.foodstack.settings.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsCache;
import io.github.ilyaslabs.foodstack.settings.core.application.SettingsService;
import io.github.ilyaslabs.foodstack.settings.core.persistence.BrandingRepository;
import io.github.ilyaslabs.foodstack.settings.core.persistence.LocaleRepository;
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
 * Everything the settings module runs on: database {@code foodstack_settings}, migrations, outbox
 * and relay, Redis cache. Lives in the module's package, so it disappears when settings is not local.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = BrandingRepository.class, mongoTemplateRef = "settingsMongoTemplate")
public class SettingsConfiguration {

    public static final String MODULE = "settings";

    @Bean
    ModuleMongo settingsMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate settingsMongoTemplate(ModuleMongo settingsMongo) {
        return settingsMongo.template();
    }

    @Bean
    MongoTransactionManager settingsTransactionManager(ModuleMongo settingsMongo) {
        return settingsMongo.transactionManager();
    }

    @Bean
    ModuleMigrations settingsMigrations(ModuleMongo settingsMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(settingsMongo, factory.connectionString(MODULE), ShardKeys.none(), properties);
    }

    @Bean
    OutboxWriter settingsOutbox(ModuleEventing eventing, ModuleMongo settingsMongo) {
        return eventing.outbox(settingsMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay settingsOutboxRelay(ModuleEventing eventing, ModuleMongo settingsMongo) {
        return eventing.relay(settingsMongo);
    }

    @Bean
    KafkaAdmin.NewTopics settingsTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    SettingsCache settingsCache(StringRedisTemplate redis, JsonMapper jsonMapper,
            @Value("${foodstack.namespace:foodstack}") String namespace,
            @Value("${foodstack.settings.cache-ttl:10m}") Duration ttl) {
        return new SettingsCache(redis, jsonMapper, namespace, ttl);
    }

    @Bean
    SettingsService settingsService(BrandingRepository brandingRepository, LocaleRepository localeRepository,
            MediaApi mediaApi, OutboxWriter settingsOutbox, ModuleMongo settingsMongo, SettingsCache settingsCache,
            Clock clock) {
        return new SettingsService(brandingRepository, localeRepository, mediaApi, settingsOutbox,
                settingsMongo.transactionTemplate(), settingsCache, clock);
    }
}
