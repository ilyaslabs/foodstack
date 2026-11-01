package io.github.ilyaslabs.foodstack.promotion.core;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.promotion.core.application.CouponService;
import io.github.ilyaslabs.foodstack.promotion.core.application.PromotionService;
import io.github.ilyaslabs.foodstack.promotion.core.events.OrderEventsListener;
import io.github.ilyaslabs.foodstack.promotion.core.persistence.PromotionRepository;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Everything the promotion module runs on: database {@code foodstack_promotion} with its shard keys,
 * migrations, outbox, relay and inbox, and the order events listener.
 */
@Configuration(proxyBeanMethods = false)
@EnableMongoRepositories(basePackageClasses = PromotionRepository.class, mongoTemplateRef = "promotionMongoTemplate")
public class PromotionConfiguration {

    public static final String MODULE = "promotion";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("promotion_promotions", "_id")
            .hashed("promotion_codes", "_id")
            .hashed("promotion_redemptions", "_id")
            .hashed("promotion_customer_usage", "_id")
            .hashed("promotion_customers", "_id");
    }

    @Bean
    ModuleMongo promotionMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate promotionMongoTemplate(ModuleMongo promotionMongo) {
        return promotionMongo.template();
    }

    @Bean
    MongoTransactionManager promotionTransactionManager(ModuleMongo promotionMongo) {
        return promotionMongo.transactionManager();
    }

    @Bean
    ModuleMigrations promotionMigrations(ModuleMongo promotionMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(promotionMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter promotionOutbox(ModuleEventing eventing, ModuleMongo promotionMongo) {
        return eventing.outbox(promotionMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay promotionOutboxRelay(ModuleEventing eventing, ModuleMongo promotionMongo) {
        return eventing.relay(promotionMongo);
    }

    @Bean
    KafkaAdmin.NewTopics promotionTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    InboxGuard promotionInbox(ModuleMongo promotionMongo, Clock clock) {
        return new InboxGuard(promotionMongo, clock);
    }

    @Bean
    CouponService promotionCouponService(ModuleMongo promotionMongo, OutboxWriter promotionOutbox, Clock clock) {
        return new CouponService(promotionMongo.template(), promotionMongo.transactionTemplate(), promotionOutbox, clock);
    }

    @Bean
    PromotionService promotionService(ModuleMongo promotionMongo, Clock clock) {
        return new PromotionService(promotionMongo.template(), promotionMongo.transactionTemplate(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderEventsListener promotionOrderEventsListener(CouponService promotionCouponService, InboxGuard promotionInbox,
            JsonMapper jsonMapper) {
        return new OrderEventsListener(promotionCouponService, promotionInbox, jsonMapper);
    }
}
