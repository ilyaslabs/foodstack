package io.github.ilyaslabs.foodstack.payment.core;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import io.github.ilyaslabs.foodstack.payment.core.application.MerchantAccounts;
import io.github.ilyaslabs.foodstack.payment.core.application.PaymentService;
import io.github.ilyaslabs.foodstack.payment.core.application.SandboxCheckout;
import io.github.ilyaslabs.foodstack.payment.core.events.CollectionEventsListener;
import io.github.ilyaslabs.foodstack.payment.core.events.OrderEventsListener;
import io.github.ilyaslabs.foodstack.payment.core.gateway.CardGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.SandboxGateway;
import io.github.ilyaslabs.foodstack.payment.core.gateway.StripeGateway;
import io.github.ilyaslabs.foodstack.payment.core.persistence.PaymentRepository;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
import io.github.ilyaslabs.foodstack.platform.kafka.ModuleEventing;
import io.github.ilyaslabs.foodstack.platform.kafka.OutboxRelay;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.OutboxWriter;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the payment module runs on: database {@code foodstack_payment}, outbox and relay, the
 * configured card gateway, the order events listener and the expiry sweep.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
@EnableMongoRepositories(basePackageClasses = PaymentRepository.class, mongoTemplateRef = "paymentMongoTemplate")
public class PaymentConfiguration {

    public static final String MODULE = "payment";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder().hashed("payment_intents", "_id").hashed("payment_store_collection", "_id");
    }

    @Bean
    ModuleMongo paymentMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTemplate paymentMongoTemplate(ModuleMongo paymentMongo) {
        return paymentMongo.template();
    }

    @Bean
    MongoTransactionManager paymentTransactionManager(ModuleMongo paymentMongo) {
        return paymentMongo.transactionManager();
    }

    @Bean
    ModuleMigrations paymentMigrations(ModuleMongo paymentMongo, ModuleMongoFactory factory, FoodstackMongoProperties properties) {
        return new ModuleMigrations(paymentMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    OutboxWriter paymentOutbox(ModuleEventing eventing, ModuleMongo paymentMongo) {
        return eventing.outbox(paymentMongo);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OutboxRelay paymentOutboxRelay(ModuleEventing eventing, ModuleMongo paymentMongo) {
        return eventing.relay(paymentMongo);
    }

    @Bean
    KafkaAdmin.NewTopics paymentTopics(ModuleEventing eventing) {
        return eventing.topics(MODULE);
    }

    @Bean
    PaymentGateways paymentGateways(PaymentProperties properties, JsonMapper jsonMapper, Clock clock,
            @Value("${foodstack.environment:development}") String environment) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(30));
        return PaymentGateways.create(properties, RestClient.builder().requestFactory(requestFactory), jsonMapper, clock,
                environment);
    }

    @Bean
    MerchantAccounts paymentMerchantAccounts(ModuleMongo paymentMongo, SettingsApi settingsApi, PaymentGateways paymentGateways,
            ObjectProvider<PiiCipher> piiCipher, PaymentProperties properties, Clock clock) {
        return new MerchantAccounts(paymentMongo.template(), paymentMongo.transactionTemplate(), settingsApi,
                paymentGateways.active(), paymentGateways.accounts(), piiCipher.getIfAvailable(), properties.publicUrl(), clock);
    }

    @Bean
    PaymentService paymentService(ModuleMongo paymentMongo, OutboxWriter paymentOutbox, SettingsApi settingsApi,
            PaymentGateways paymentGateways, PaymentProperties properties, Clock clock,
            MerchantAccounts paymentMerchantAccounts) {
        return new PaymentService(paymentMongo.template(), paymentMongo.transactionTemplate(), paymentOutbox, settingsApi,
                paymentGateways.active(), properties.sessionTtl(), clock, paymentMerchantAccounts);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    CollectionEventsListener paymentCollectionEventsListener(MerchantAccounts paymentMerchantAccounts, JsonMapper jsonMapper) {
        return new CollectionEventsListener(paymentMerchantAccounts, jsonMapper);
    }

    @Bean
    SandboxCheckout paymentSandboxCheckout(PaymentGateways paymentGateways, PaymentService paymentService,
            ModuleMongo paymentMongo) {
        return new SandboxCheckout(paymentGateways.sandbox(), paymentService, paymentMongo.template());
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderEventsListener paymentOrderEventsListener(PaymentService paymentService, JsonMapper jsonMapper) {
        return new OrderEventsListener(paymentService, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask paymentExpirySweep(PaymentService paymentService) {
        return PeriodicTask.start("payment-expiry", Duration.ofSeconds(30), Duration.ofMinutes(1), paymentService::expireStale);
    }

    /**
     * The configured gateway. The sandbox is refused in production; Stripe needs both its keys.
     *
     * @param active the gateway in use, or {@code null} for none (cash only)
     * @param sandbox the sandbox when it is the one in use, otherwise {@code null}
     * @param accounts opens the gateway in use on a store's own merchant account, or {@code null} for none
     */
    public record PaymentGateways(CardGateway active, SandboxGateway sandbox, CardGateway.AccountGateways accounts) {

        static PaymentGateways create(PaymentProperties properties, RestClient.Builder builder, JsonMapper jsonMapper, Clock clock,
                String environment) {
            return switch (properties.gateway()) {
                case "none" -> new PaymentGateways(null, null, null);
                case SandboxGateway.ID -> {
                    if ("production".equalsIgnoreCase(environment)) {
                        throw new IllegalStateException("The sandbox payment gateway cannot run in production; "
                                + "set PAYMENT_GATEWAY to stripe or none");
                    }
                    String secret = (properties.sandboxSecret() != null && !properties.sandboxSecret().isBlank())
                            ? properties.sandboxSecret() : UUID.randomUUID().toString();
                    SandboxGateway sandbox = new SandboxGateway(properties.publicUrl(), secret, jsonMapper);
                    yield new PaymentGateways(sandbox, sandbox, (secretKey, webhookSecret) -> sandbox.forAccount(secretKey));
                }
                case StripeGateway.ID -> {
                    PaymentProperties.Stripe stripe = properties.stripe();
                    if (blank(stripe.secretKey()) || blank(stripe.webhookSecret())) {
                        throw new IllegalStateException("PAYMENT_GATEWAY=stripe needs STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET");
                    }
                    yield new PaymentGateways(new StripeGateway(builder, stripe.apiUrl(), stripe.secretKey(),
                            stripe.webhookSecret(), jsonMapper, clock), null,
                            (secretKey, webhookSecret) -> new StripeGateway(builder, stripe.apiUrl(), secretKey, webhookSecret,
                                    jsonMapper, clock));
                }
                default -> throw new IllegalStateException("Unknown payment gateway " + properties.gateway()
                        + "; use none, sandbox or stripe");
            };
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }
}
