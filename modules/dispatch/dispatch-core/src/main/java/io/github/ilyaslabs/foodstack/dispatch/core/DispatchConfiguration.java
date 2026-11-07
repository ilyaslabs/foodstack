package io.github.ilyaslabs.foodstack.dispatch.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.dispatch.core.application.CustomerErasure;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchLive;
import io.github.ilyaslabs.foodstack.dispatch.core.application.DispatchService;
import io.github.ilyaslabs.foodstack.dispatch.core.events.IdentityEventsListener;
import io.github.ilyaslabs.foodstack.dispatch.core.events.OrderEventsListener;
import io.github.ilyaslabs.foodstack.geo.api.GeoApi;
import io.github.ilyaslabs.foodstack.platform.kafka.BucketLeases;
import io.github.ilyaslabs.foodstack.platform.kafka.LiveChannels;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.kafka.PartitionedTask;
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
import io.github.ilyaslabs.foodstack.rider.api.RiderApi;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the dispatch module runs on: database {@code foodstack_dispatch} with its shard keys,
 * migrations, outbox, relay and inbox, offer locks and live channels in Redis, the order events
 * listener and the matcher, which one replica runs at a time.
 */
@Configuration(proxyBeanMethods = false)
public class DispatchConfiguration {

    public static final String MODULE = "dispatch";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder().hashed("dispatch_jobs", "_id").hashed("dispatch_active", "_id");
    }

    @Bean
    ModuleMongo dispatchMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate dispatchMongoTemplate(ModuleMongo dispatchMongo) {
        return dispatchMongo.template();
    }

    @Bean
    MongoTransactionManager dispatchTransactionManager(ModuleMongo dispatchMongo) {
        return dispatchMongo.transactionManager();
    }

    @Bean
    ModuleMigrations dispatchMigrations(ModuleMongo dispatchMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(dispatchMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter dispatchOutbox(ModuleEventing eventing, ModuleMongo dispatchMongo) {
        return eventing.outbox(dispatchMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay dispatchOutboxRelay(ModuleEventing eventing, ModuleMongo dispatchMongo) {
        return eventing.relay(dispatchMongo);
    }

    @Bean
    KafkaAdmin.NewTopics dispatchTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    InboxGuard dispatchInbox(ModuleMongo dispatchMongo, Clock clock) {
        return new InboxGuard(dispatchMongo, clock);
    }

    @Bean
    DispatchLive dispatchLive(StringRedisTemplate redis, LiveChannels liveChannels,
            @Value("${foodstack.namespace:foodstack}") String namespace) {
        return new DispatchLive(redis, liveChannels, namespace);
    }

    @Bean
    DispatchService dispatchService(ModuleMongo dispatchMongo, OutboxWriter dispatchOutbox, DispatchLive dispatchLive,
            RiderApi riderApi, SettingsApi settingsApi, GeoApi geoApi, ServiceCalls serviceCalls, RateLimiter rateLimiter,
            PiiCipher piiCipher, Clock clock) {
        return new DispatchService(dispatchMongo.template(), dispatchMongo.transactionTemplate(), dispatchOutbox, dispatchLive,
                riderApi, settingsApi, geoApi, serviceCalls, rateLimiter, piiCipher, clock);
    }

    @Bean
    CustomerErasure dispatchCustomerErasure(ModuleMongo dispatchMongo) {
        return new CustomerErasure(dispatchMongo.template());
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    IdentityEventsListener dispatchIdentityEventsListener(CustomerErasure dispatchCustomerErasure, JsonMapper jsonMapper) {
        return new IdentityEventsListener(dispatchCustomerErasure, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderEventsListener dispatchOrderEventsListener(DispatchService dispatchService, InboxGuard dispatchInbox,
            JsonMapper jsonMapper) {
        return new OrderEventsListener(dispatchService, dispatchInbox, jsonMapper);
    }

    /** The matcher, every two seconds, on every replica over the work buckets it holds (TDD 7.5). */
    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PartitionedTask dispatchMatcher(DispatchService dispatchService, StringRedisTemplate redis,
            @Value("${foodstack.namespace:foodstack}") String namespace, Clock clock) {
        BucketLeases leases = new BucketLeases(redis, namespace, "dispatch:matcher", Duration.ofSeconds(15), clock);
        return PartitionedTask.start(leases, Duration.ofSeconds(5), Duration.ofSeconds(2), dispatchService::match);
    }
}
