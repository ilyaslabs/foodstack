package io.github.ilyaslabs.foodstack.rider.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.media.api.MediaApi;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderActivity;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderOnboarding;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderPresence;
import io.github.ilyaslabs.foodstack.rider.core.application.RiderService;
import io.github.ilyaslabs.foodstack.rider.core.events.DispatchEventsListener;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Everything the rider module runs on: database {@code foodstack_rider} with its shard keys,
 * migrations, outbox, relay and inbox, presence in Redis, the dispatch events listener and the
 * sweep that takes quiet riders offline.
 */
@Configuration(proxyBeanMethods = false)
public class RiderConfiguration {

    public static final String MODULE = "rider";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("rider_riders", "_id")
            .hashed("rider_locations", "riderId")
            .hashed("rider_assignments", "_id")
            .hashed("rider_earnings", "riderId")
            .hashed("rider_onboarding", "_id");
    }

    @Bean
    ModuleMongo riderMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate riderMongoTemplate(ModuleMongo riderMongo) {
        return riderMongo.template();
    }

    @Bean
    MongoTransactionManager riderTransactionManager(ModuleMongo riderMongo) {
        return riderMongo.transactionManager();
    }

    @Bean
    ModuleMigrations riderMigrations(ModuleMongo riderMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(riderMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter riderOutbox(ModuleEventing eventing, ModuleMongo riderMongo) {
        return eventing.outbox(riderMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay riderOutboxRelay(ModuleEventing eventing, ModuleMongo riderMongo) {
        return eventing.relay(riderMongo);
    }

    @Bean
    KafkaAdmin.NewTopics riderTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    InboxGuard riderInbox(ModuleMongo riderMongo, Clock clock) {
        return new InboxGuard(riderMongo, clock);
    }

    @Bean
    RiderPresence riderPresence(StringRedisTemplate redis, LiveChannels liveChannels,
            @Value("${foodstack.namespace:foodstack}") String namespace) {
        return new RiderPresence(redis, liveChannels, namespace);
    }

    @Bean
    RiderService riderService(ModuleMongo riderMongo, OutboxWriter riderOutbox, IdentityApi identityApi,
            ServiceCalls serviceCalls, RiderPresence riderPresence, PiiCipher piiCipher, Clock clock, GeoApi geoApi) {
        return new RiderService(riderMongo.template(), riderMongo.transactionTemplate(), riderOutbox, identityApi, serviceCalls,
                riderPresence, piiCipher, clock, geoApi);
    }

    @Bean
    RiderActivity riderActivity(ModuleMongo riderMongo, OutboxWriter riderOutbox, RiderService riderService,
            RiderPresence riderPresence, RateLimiter rateLimiter, SettingsApi settingsApi, GeoApi geoApi,
            ServiceCalls serviceCalls, Clock clock) {
        return new RiderActivity(riderMongo.template(), riderMongo.transactionTemplate(), riderOutbox, riderService,
                riderPresence, rateLimiter, settingsApi, geoApi, serviceCalls, clock);
    }

    @Bean
    RiderOnboarding riderOnboarding(ModuleMongo riderMongo, RiderService riderService, MediaApi mediaApi,
            ServiceCalls serviceCalls, PiiCipher piiCipher, Clock clock) {
        return new RiderOnboarding(riderMongo.template(), riderService, mediaApi, serviceCalls, piiCipher, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    DispatchEventsListener riderDispatchEventsListener(RiderActivity riderActivity, InboxGuard riderInbox, JsonMapper jsonMapper,
            Clock clock) {
        return new DispatchEventsListener(riderActivity, riderInbox, jsonMapper, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask riderStaleSweep(RiderActivity riderActivity) {
        return PeriodicTask.start("rider-stale-sweep", Duration.ofSeconds(60), Duration.ofSeconds(60), riderActivity::sweepStale);
    }
}
