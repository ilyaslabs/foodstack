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

    @Bean
    KafkaAdmin.NewTopics dltestTopics(ModuleEventing eventing) {
        return eventing.topics("dltest");
    }

    @Bean
    FailingListener failingListener() {
        return new FailingListener();
    }

    @Bean
    OtherListener otherListener() {
        return new OtherListener();
    }

    /** Fails every record whose value is "poison", so it ends in the dead-letter topic. */
    static class FailingListener {

        final java.util.concurrent.atomic.AtomicInteger poisonAttempts = new java.util.concurrent.atomic.AtomicInteger();

        final java.util.List<String> handled = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.kafka.annotation.KafkaListener(id = "dltest-listener", groupId = "dltest",
                topics = "foodstack.dltest.events", properties = "auto.offset.reset=earliest")
        void onEvent(String value) {
            if ("poison".equals(value)) {
                this.poisonAttempts.incrementAndGet();
                throw new IllegalStateException("cannot handle poison");
            }
            this.handled.add(value);
        }
    }

    /** A second group on the same topic, which a replay meant for {@code dltest} must skip. */
    static class OtherListener {

        final java.util.List<String> handled = new java.util.concurrent.CopyOnWriteArrayList<>();

        @org.springframework.kafka.annotation.KafkaListener(id = "dltest-other-listener", groupId = "dltest-other",
                topics = "foodstack.dltest.events", properties = "auto.offset.reset=earliest")
        void onEvent(String value) {
            this.handled.add(value);
        }
    }
}
