package io.github.ilyaslabs.foodstack.review.core;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

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
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.review.core.application.ReviewService;
import io.github.ilyaslabs.foodstack.review.core.domain.BlockedWords;
import io.github.ilyaslabs.foodstack.review.core.events.FinishedOrdersListener;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the review module runs on: database {@code foodstack_review} with its shard keys,
 * migrations, outbox and relay, the finished-orders listener and the review service.
 */
@Configuration(proxyBeanMethods = false)
public class ReviewConfiguration {

    public static final String MODULE = "review";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("review_reviews", "storeId")
            .hashed("review_orders", "_id")
            .hashed("review_store_stats", "_id")
            .hashed("review_rider_ratings", "riderId")
            .hashed("review_rider_stats", "_id");
    }

    @Bean
    ModuleMongo reviewMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate reviewMongoTemplate(ModuleMongo reviewMongo) {
        return reviewMongo.template();
    }

    @Bean
    MongoTransactionManager reviewTransactionManager(ModuleMongo reviewMongo) {
        return reviewMongo.transactionManager();
    }

    @Bean
    ModuleMigrations reviewMigrations(ModuleMongo reviewMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(reviewMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter reviewOutbox(ModuleEventing eventing, ModuleMongo reviewMongo) {
        return eventing.outbox(reviewMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay reviewOutboxRelay(ModuleEventing eventing, ModuleMongo reviewMongo) {
        return eventing.relay(reviewMongo);
    }

    @Bean
    KafkaAdmin.NewTopics reviewTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    /**
     * @param window how long after delivery or collection an order can be rated
     * @param extraWords the operator's own blocked words, added to the built-in list
     */
    @Bean
    ReviewService reviewService(ModuleMongo reviewMongo, OutboxWriter reviewOutbox, IdentityApi identityApi,
            MediaApi mediaApi, ServiceCalls serviceCalls, Clock clock,
            @Value("${foodstack.review.window:P14D}") Duration window,
            @Value("${foodstack.review.blocked-words:}") List<String> extraWords) {
        return new ReviewService(reviewMongo.template(), reviewMongo.transactionTemplate(), reviewOutbox, identityApi,
                mediaApi, serviceCalls, BlockedWords.withBuiltIn(extraWords), window, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    FinishedOrdersListener reviewFinishedOrdersListener(ReviewService reviewService, JsonMapper jsonMapper, Clock clock) {
        return new FinishedOrdersListener(reviewService, jsonMapper, clock);
    }
}
