package io.github.ilyaslabs.foodstack.identity.core;

import java.time.Clock;

import io.github.ilyaslabs.foodstack.identity.core.application.BootstrapAdmin;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyRepository;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Everything the identity module runs on: database {@code foodstack_identity}, migrations, outbox
 * and relay, and the user service. Lives in the module's package, so it disappears when identity
 * is not local.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
@EnableMongoRepositories(basePackageClasses = UserRepository.class, mongoTemplateRef = "identityMongoTemplate")
public class IdentityConfiguration {

    public static final String MODULE = "identity";

    @Bean
    ModuleMongo identityMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate identityMongoTemplate(ModuleMongo identityMongo) {
        return identityMongo.template();
    }

    @Bean
    MongoTransactionManager identityTransactionManager(ModuleMongo identityMongo) {
        return identityMongo.transactionManager();
    }

    @Bean
    ModuleMigrations identityMigrations(ModuleMongo identityMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(identityMongo, factory.connectionString(MODULE),
                ShardKeys.builder().hashed("identity_users", "_id").ranged("identity_login_keys", "loginKey"), properties);
    }

    @Bean
    OutboxWriter identityOutbox(ModuleEventing eventing, ModuleMongo identityMongo) {
        return eventing.outbox(identityMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay identityOutboxRelay(ModuleEventing eventing, ModuleMongo identityMongo) {
        return eventing.relay(identityMongo);
    }

    @Bean
    KafkaAdmin.NewTopics identityTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    UserService identityUserService(UserRepository users, LoginKeyRepository loginKeys, PiiCipher piiCipher,
            PasswordEncoder identityPasswordEncoder, OutboxWriter identityOutbox, ModuleMongo identityMongo,
            TokenRevocations tokenRevocations, Clock clock) {
        return new UserService(users, loginKeys, piiCipher, identityPasswordEncoder, identityOutbox,
                identityMongo.transactionTemplate(), tokenRevocations, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    BootstrapAdmin identityBootstrapAdmin(UserService identityUserService, IdentityProperties properties) {
        return new BootstrapAdmin(identityUserService, properties);
    }
}
