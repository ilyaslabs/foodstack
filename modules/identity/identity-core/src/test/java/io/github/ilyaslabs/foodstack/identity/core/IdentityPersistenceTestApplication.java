package io.github.ilyaslabs.foodstack.identity.core;

import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/** The persistence part of IdentityConfiguration, without Kafka and Redis. */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableMongoRepositories(basePackageClasses = UserRepository.class, mongoTemplateRef = "identityMongoTemplate")
public class IdentityPersistenceTestApplication {

    @Bean
    ModuleMongo identityMongo(ModuleMongoFactory factory) {
        return factory.create("identity");
    }

    @Bean
    MongoTemplate identityMongoTemplate(ModuleMongo identityMongo) {
        return identityMongo.template();
    }

    @Bean
    ModuleMigrations identityMigrations(ModuleMongo identityMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(identityMongo, factory.connectionString("identity"),
                ShardKeys.builder().hashed("identity_users", "_id").ranged("identity_login_keys", "loginKey"), properties);
    }
}
