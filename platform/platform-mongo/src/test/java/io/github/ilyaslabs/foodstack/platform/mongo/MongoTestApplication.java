package io.github.ilyaslabs.foodstack.platform.mongo;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;

/** A context with one fake module, {@code testmod}, wired the way real modules wire themselves. */
@SpringBootConfiguration
@EnableAutoConfiguration
class MongoTestApplication {

    @Bean
    ModuleMongo testmodMongo(ModuleMongoFactory factory) {
        return factory.create("testmod");
    }

    @Bean
    ModuleMigrations testmodMigrations(ModuleMongo testmodMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(testmodMongo, factory.connectionString("testmod"),
                ShardKeys.builder().hashed("testmod_things", "_id"), properties);
    }

    @Bean
    OutboxWriter testmodOutbox(ModuleMongo testmodMongo, JsonMapper jsonMapper, java.time.Clock clock) {
        return new OutboxWriter(testmodMongo, jsonMapper, clock);
    }

    @Bean
    InboxGuard testmodInbox(ModuleMongo testmodMongo, java.time.Clock clock) {
        return new InboxGuard(testmodMongo, clock);
    }
}
