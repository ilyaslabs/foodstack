package io.github.ilyaslabs.foodstack.platform.kafka;

import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaAdmin;

/** One fake module, {@code relaytest}, wired like a real module. */
@SpringBootConfiguration
@EnableAutoConfiguration
class KafkaTestApplication {

    @Bean
    ModuleMongo relaytestMongo(ModuleMongoFactory factory) {
        return factory.create("relaytest");
    }

    @Bean
    ModuleMigrations relaytestMigrations(ModuleMongo relaytestMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(relaytestMongo, factory.connectionString("relaytest"), ShardKeys.none(), properties);
    }

    @Bean
    OutboxWriter relaytestOutbox(ModuleEventing eventing, ModuleMongo relaytestMongo) {
        return eventing.outbox(relaytestMongo);
    }

    @Bean
    OutboxRelay relaytestOutboxRelay(ModuleEventing eventing, ModuleMongo relaytestMongo) {
        return eventing.relay(relaytestMongo);
    }

    @Bean
    KafkaAdmin.NewTopics relaytestTopics(ModuleEventing eventing) {
        return eventing.topics("relaytest");
    }
}
