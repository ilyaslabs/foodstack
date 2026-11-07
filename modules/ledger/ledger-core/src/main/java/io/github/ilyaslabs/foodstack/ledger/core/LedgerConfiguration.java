package io.github.ilyaslabs.foodstack.ledger.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.ledger.core.application.CreditLimits;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerPosting;
import io.github.ilyaslabs.foodstack.ledger.core.application.LedgerReports;
import io.github.ilyaslabs.foodstack.ledger.core.application.Rollups;
import io.github.ilyaslabs.foodstack.ledger.core.events.DispatchEventsListener;
import io.github.ilyaslabs.foodstack.ledger.core.events.OrderEventsListener;
import io.github.ilyaslabs.foodstack.ledger.core.events.PaymentEventsListener;
import io.github.ilyaslabs.foodstack.ledger.core.events.StoreEventsListener;
import io.github.ilyaslabs.foodstack.platform.kafka.BucketLeases;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.kafka.PartitionedTask;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
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
 * Everything the ledger module runs on: database {@code foodstack_ledger} with its shard keys,
 * migrations, outbox and relay, the order, payment and dispatch listeners, and the roll-up workers.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerConfiguration {

    public static final String MODULE = "ledger";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("ledger_transactions", "partition")
            .hashed("ledger_daily", "accountId")
            .hashed("ledger_accounts", "_id")
            .hashed("ledger_orders", "_id")
            .hashed("ledger_settlements", "accountId")
            .hashed("ledger_credit", "_id");
    }

    @Bean
    ModuleMongo ledgerMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate ledgerMongoTemplate(ModuleMongo ledgerMongo) {
        return ledgerMongo.template();
    }

    @Bean
    MongoTransactionManager ledgerTransactionManager(ModuleMongo ledgerMongo) {
        return ledgerMongo.transactionManager();
    }

    @Bean
    ModuleMigrations ledgerMigrations(ModuleMongo ledgerMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(ledgerMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter ledgerOutbox(ModuleEventing eventing, ModuleMongo ledgerMongo) {
        return eventing.outbox(ledgerMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay ledgerOutboxRelay(ModuleEventing eventing, ModuleMongo ledgerMongo) {
        return eventing.relay(ledgerMongo);
    }

    @Bean
    KafkaAdmin.NewTopics ledgerTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    LedgerPosting ledgerPosting(ModuleMongo ledgerMongo, SettingsApi settingsApi, Clock clock) {
        return new LedgerPosting(ledgerMongo.template(), settingsApi, clock);
    }

    @Bean
    Rollups ledgerRollups(ModuleMongo ledgerMongo) {
        return new Rollups(ledgerMongo.template(), ledgerMongo.transactionTemplate());
    }

    @Bean
    CreditLimits ledgerCreditLimits(ModuleMongo ledgerMongo, OutboxWriter ledgerOutbox, SettingsApi settingsApi, Clock clock) {
        return new CreditLimits(ledgerMongo.template(), ledgerMongo.transactionTemplate(), ledgerOutbox, settingsApi, clock);
    }

    @Bean
    LedgerReports ledgerReports(ModuleMongo ledgerMongo, OutboxWriter ledgerOutbox, LedgerPosting ledgerPosting,
            SettingsApi settingsApi, Clock clock, CreditLimits ledgerCreditLimits) {
        return new LedgerReports(ledgerMongo.template(), ledgerMongo.transactionTemplate(), ledgerOutbox, ledgerPosting,
                settingsApi, clock, ledgerCreditLimits);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderEventsListener ledgerOrderEventsListener(LedgerPosting ledgerPosting, JsonMapper jsonMapper, Clock clock,
            CreditLimits ledgerCreditLimits) {
        return new OrderEventsListener(ledgerPosting, jsonMapper, clock, ledgerCreditLimits);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    StoreEventsListener ledgerStoreEventsListener(CreditLimits ledgerCreditLimits, JsonMapper jsonMapper) {
        return new StoreEventsListener(ledgerCreditLimits, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PaymentEventsListener ledgerPaymentEventsListener(LedgerPosting ledgerPosting, JsonMapper jsonMapper, Clock clock) {
        return new PaymentEventsListener(ledgerPosting, jsonMapper, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    DispatchEventsListener ledgerDispatchEventsListener(LedgerPosting ledgerPosting, JsonMapper jsonMapper, Clock clock) {
        return new DispatchEventsListener(ledgerPosting, jsonMapper, clock);
    }

    /** Daily roll-ups (TDD 7.5): each replica rolls up the buckets it holds; one replica alone rolls up all. */
    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PartitionedTask ledgerRollupWorker(Rollups ledgerRollups, StringRedisTemplate redis,
            @Value("${foodstack.namespace:foodstack}") String namespace,
            @Value("${foodstack.ledger.rollup-interval:PT30S}") Duration interval, Clock clock) {
        BucketLeases leases = new BucketLeases(redis, namespace, "ledger:rollups", Duration.ofSeconds(30), clock);
        return PartitionedTask.start(leases, Duration.ofSeconds(10), interval, ledgerRollups::rollUp);
    }
}
