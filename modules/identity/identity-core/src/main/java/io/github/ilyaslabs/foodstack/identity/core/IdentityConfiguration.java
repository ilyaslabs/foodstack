package io.github.ilyaslabs.foodstack.identity.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.identity.core.application.AccountDeletion;
import io.github.ilyaslabs.foodstack.identity.core.application.AddressBook;
import io.github.ilyaslabs.foodstack.identity.core.application.AuditLog;
import io.github.ilyaslabs.foodstack.identity.core.application.BootstrapAdmin;
import io.github.ilyaslabs.foodstack.identity.core.application.EmailCodeService;
import io.github.ilyaslabs.foodstack.identity.core.application.SessionService;
import io.github.ilyaslabs.foodstack.identity.core.application.UserDirectory;
import io.github.ilyaslabs.foodstack.identity.core.application.UserService;
import io.github.ilyaslabs.foodstack.identity.core.events.AuditListener;
import io.github.ilyaslabs.foodstack.identity.core.persistence.LoginKeyRepository;
import io.github.ilyaslabs.foodstack.identity.core.persistence.UserRepository;
import io.github.ilyaslabs.foodstack.identity.core.security.AccessTokens;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.FoodstackSecurityProperties;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.RateLimiter;
import io.github.ilyaslabs.foodstack.platform.security.TokenRevocations;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;

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

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("identity_users", "_id")
            .ranged("identity_login_keys", "loginKey")
            .hashed("identity_addresses", "userId")
            .hashed("identity_refresh_tokens", "_id")
            .hashed("identity_audit_log", "_id");
    }

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
                shardKeys(), properties);
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
    AuditLog identityAuditLog(ModuleMongo identityMongo, UserService identityUserService, Clock clock) {
        return new AuditLog(identityMongo.template(), identityUserService, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    AuditListener identityAuditListener(AuditLog identityAuditLog, JsonMapper jsonMapper) {
        return new AuditListener(identityAuditLog, jsonMapper);
    }

    @Bean
    UserService identityUserService(UserRepository users, LoginKeyRepository loginKeys, PiiCipher piiCipher,
            PasswordEncoder identityPasswordEncoder, OutboxWriter identityOutbox, ModuleMongo identityMongo,
            TokenRevocations tokenRevocations, Clock clock) {
        return new UserService(users, loginKeys, piiCipher, identityPasswordEncoder, identityOutbox,
                identityMongo.transactionTemplate(), tokenRevocations, clock);
    }

    @Bean
    AccountDeletion identityAccountDeletion(ModuleMongo identityMongo, OutboxWriter identityOutbox,
            UserService identityUserService, TokenRevocations tokenRevocations, IdentityProperties properties, Clock clock) {
        return new AccountDeletion(identityMongo.template(), identityMongo.transactionTemplate(), identityOutbox,
                identityUserService, tokenRevocations, properties.getDeletionGrace(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask identityAccountDeletionSweep(AccountDeletion identityAccountDeletion) {
        return PeriodicTask.start("account-deletions", Duration.ofMinutes(2), Duration.ofMinutes(30),
                () -> identityAccountDeletion.runDue(200));
    }

    @Bean
    EmailCodeService identityEmailCodeService(UserService identityUserService, StringRedisTemplate redis,
            RateLimiter rateLimiter, PiiCipher piiCipher, OutboxWriter identityOutbox, ModuleMongo identityMongo,
            @Value("${foodstack.namespace:foodstack}") String namespace, Clock clock) {
        return new EmailCodeService(identityUserService, redis, rateLimiter, piiCipher, identityOutbox,
                identityMongo.transactionTemplate(), namespace, clock);
    }

    @Bean
    AccessTokens identityAccessTokens(JWKSource<SecurityContext> identityJwkSource, FoodstackSecurityProperties security,
            IdentityProperties properties, Clock clock) {
        return new AccessTokens(identityJwkSource, security.getIssuer(), properties.getAccessTokenTtl(), clock);
    }

    @Bean
    SessionService identitySessionService(ModuleMongo identityMongo, UserService identityUserService,
            EmailCodeService identityEmailCodeService, AccessTokens identityAccessTokens, RateLimiter rateLimiter,
            PiiCipher piiCipher, Clock clock, AuditLog identityAuditLog) {
        return new SessionService(identityMongo.template(), identityUserService, identityEmailCodeService,
                identityAccessTokens, rateLimiter, piiCipher, clock, identityAuditLog);
    }

    @Bean
    UserDirectory identityUserDirectory(UserService identityUserService, UserRepository users, ModuleMongo identityMongo,
            Clock clock) {
        return new UserDirectory(identityUserService, users, identityMongo.template(), clock);
    }

    @Bean
    AddressBook identityAddressBook(ModuleMongo identityMongo, Clock clock) {
        return new AddressBook(identityMongo.template(), identityMongo.transactionTemplate(), clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    BootstrapAdmin identityBootstrapAdmin(UserService identityUserService, IdentityProperties properties) {
        return new BootstrapAdmin(identityUserService, properties);
    }
}
