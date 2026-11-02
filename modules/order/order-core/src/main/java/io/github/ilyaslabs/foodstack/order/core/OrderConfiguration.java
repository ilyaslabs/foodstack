package io.github.ilyaslabs.foodstack.order.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.cart.api.CartApi;
import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.order.core.application.OrderLifecycleService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderLiveUpdates;
import io.github.ilyaslabs.foodstack.order.core.application.OrderPlacementService;
import io.github.ilyaslabs.foodstack.order.core.application.OrderProjections;
import io.github.ilyaslabs.foodstack.order.core.application.OrderService;
import io.github.ilyaslabs.foodstack.order.core.events.DispatchEventsListener;
import io.github.ilyaslabs.foodstack.order.core.events.OrderProjectionListener;
import io.github.ilyaslabs.foodstack.order.core.events.PaymentEventsListener;
import io.github.ilyaslabs.foodstack.payment.api.PaymentApi;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
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
import io.github.ilyaslabs.foodstack.promotion.api.PromotionApi;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Wiring of the order module (TDD 5.2): its database, outbox and relay, placement, the state
 * machine, read projections, live updates over Redis, the payment events listener and the
 * accept-timeout sweep.
 */
@Configuration(proxyBeanMethods = false)
public class OrderConfiguration {

    public static final String MODULE = "order";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("order_orders", "_id")
            .hashed("order_by_customer", "customerId")
            .ranged("order_by_store", "storeId", "placedAt")
            .hashed("order_placement_keys", "_id");
    }

    @Bean
    ModuleMongo orderMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTransactionManager orderTransactionManager(ModuleMongo orderMongo) {
        return orderMongo.transactionManager();
    }

    @Bean
    ModuleMigrations orderMigrations(ModuleMongo orderMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(orderMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter orderOutbox(ModuleEventing eventing, ModuleMongo orderMongo) {
        return eventing.outbox(orderMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay orderOutboxRelay(ModuleEventing eventing, ModuleMongo orderMongo) {
        return eventing.relay(orderMongo);
    }

    @Bean
    KafkaAdmin.NewTopics orderTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    InboxGuard orderInbox(ModuleMongo orderMongo, Clock clock) {
        return new InboxGuard(orderMongo, clock);
    }

    @Bean(destroyMethod = "destroy")
    RedisMessageListenerContainer orderLiveListeners(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }

    @Bean
    OrderLiveUpdates orderLiveUpdates(StringRedisTemplate redis, RedisMessageListenerContainer orderLiveListeners,
            @Value("${foodstack.namespace:foodstack}") String namespace) {
        return new OrderLiveUpdates(redis, orderLiveListeners, namespace);
    }

    @Bean
    OrderLifecycleService orderLifecycleService(ModuleMongo orderMongo, OutboxWriter orderOutbox, OrderLiveUpdates orderLiveUpdates,
            SettingsApi settingsApi, Clock clock) {
        return new OrderLifecycleService(orderMongo.template(), orderMongo.transactionTemplate(), orderOutbox, orderLiveUpdates,
                settingsApi, clock);
    }

    @Bean
    OrderPlacementService orderPlacementService(ModuleMongo orderMongo, OutboxWriter orderOutbox, CartApi cartApi,
            PromotionApi promotionApi, PaymentApi paymentApi, IdentityApi identityApi,
            OrderLifecycleService orderLifecycleService, OrderLiveUpdates orderLiveUpdates, PiiCipher piiCipher,
            ObjectProvider<RateLimiter> rateLimiter,
            @Value("${foodstack.order.web-url:${foodstack.security.issuer:http://localhost:8080}}") String webUrl, Clock clock) {
        return new OrderPlacementService(orderMongo.template(), orderMongo.transactionTemplate(), orderOutbox, cartApi,
                promotionApi, paymentApi, identityApi, orderLifecycleService, orderLiveUpdates, piiCipher,
                rateLimiter.getIfAvailable(), webUrl, clock);
    }

    @Bean
    OrderService orderService(ModuleMongo orderMongo, OrderLifecycleService orderLifecycleService, PiiCipher piiCipher) {
        return new OrderService(orderMongo.template(), orderLifecycleService, piiCipher);
    }

    @Bean
    OrderProjections orderProjections(ModuleMongo orderMongo) {
        return new OrderProjections(orderMongo.template());
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderProjectionListener orderProjectionListener(OrderProjections orderProjections, JsonMapper jsonMapper) {
        return new OrderProjectionListener(orderProjections, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PaymentEventsListener orderPaymentEventsListener(OrderLifecycleService orderLifecycleService,
            OrderPlacementService orderPlacementService, InboxGuard orderInbox, ServiceCalls serviceCalls, JsonMapper jsonMapper) {
        return new PaymentEventsListener(orderLifecycleService, orderPlacementService, orderInbox, serviceCalls, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    DispatchEventsListener orderDispatchEventsListener(OrderLifecycleService orderLifecycleService, InboxGuard orderInbox,
            JsonMapper jsonMapper) {
        return new DispatchEventsListener(orderLifecycleService, orderInbox, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask orderAcceptTimeoutSweep(OrderLifecycleService orderLifecycleService) {
        return PeriodicTask.start("order-accept-timeout", Duration.ofSeconds(20), Duration.ofSeconds(15),
                orderLifecycleService::rejectUnanswered);
    }
}
