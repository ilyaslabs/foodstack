package io.github.ilyaslabs.foodstack.notification.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.notification.core.application.AccountMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.InboxService;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService;
import io.github.ilyaslabs.foodstack.notification.core.application.OrderMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.Templates;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport;
import io.github.ilyaslabs.foodstack.notification.core.channel.SmtpMailTransport;
import io.github.ilyaslabs.foodstack.notification.core.events.IdentityNotificationsListener;
import io.github.ilyaslabs.foodstack.notification.core.events.OrderNotificationsListener;
import io.github.ilyaslabs.foodstack.order.api.OrderApi;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Wiring of the notification module (TDD 5.2): its database, the message texts, email over SMTP
 * (when {@code SMTP_HOST} is set), the event listeners and the email retry worker. It publishes no
 * events of its own.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationConfiguration {

    public static final String MODULE = "notification";

    /** Shard keys of TDD 6.3, applied when sharding is switched on. */
    public static ShardKeys shardKeys() {
        return ShardKeys.builder()
            .hashed("notification_inbox", "userId")
            .hashed("notification_deliveries", "_id")
            .hashed("notification_preferences", "_id");
    }

    @Bean
    ModuleMongo notificationMongo(ModuleMongoFactory factory) {
        return factory.create(MODULE);
    }

    @Bean
    MongoTransactionManager notificationTransactionManager(ModuleMongo notificationMongo) {
        return notificationMongo.transactionManager();
    }

    @Bean
    ModuleMigrations notificationMigrations(ModuleMongo notificationMongo, ModuleMongoFactory factory,
            FoodstackMongoProperties properties) {
        return new ModuleMigrations(notificationMongo, factory.connectionString(MODULE), shardKeys(), properties);
    }

    @Bean
    Templates notificationTemplates(JsonMapper jsonMapper) {
        return new Templates(jsonMapper);
    }

    @Bean
    NotificationService notificationService(ModuleMongo notificationMongo, Templates notificationTemplates, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls, PiiCipher piiCipher, ObjectProvider<JavaMailSender> mailSender,
            @Value("${foodstack.notification.from-address:}") String fromAddress,
            @Value("${foodstack.notification.from-name:FoodStack}") String fromName,
            @Value("${foodstack.order.web-url:${foodstack.security.issuer:http://localhost:8080}}") String webUrl,
            @Value("${foodstack.notification.admin-url:${foodstack.security.issuer:http://localhost:8080}/admin}") String adminUrl,
            Clock clock) {
        JavaMailSender sender = mailSender.getIfAvailable();
        MailTransport transport = (sender != null && !fromAddress.isBlank()) ? new SmtpMailTransport(sender, fromAddress, fromName)
                : null;
        return new NotificationService(notificationMongo.template(), notificationTemplates, settingsApi, identityApi, serviceCalls,
                piiCipher, transport, webUrl, adminUrl, clock);
    }

    @Bean
    InboxService notificationInboxService(ModuleMongo notificationMongo, Clock clock) {
        return new InboxService(notificationMongo.template(), clock);
    }

    @Bean
    OrderMessages notificationOrderMessages(NotificationService notificationService, Templates notificationTemplates,
            SettingsApi settingsApi, OrderApi orderApi, ServiceCalls serviceCalls) {
        return new OrderMessages(notificationService, notificationTemplates, settingsApi, orderApi, serviceCalls);
    }

    @Bean
    AccountMessages notificationAccountMessages(NotificationService notificationService, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls) {
        return new AccountMessages(notificationService, settingsApi, identityApi, serviceCalls);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderNotificationsListener notificationOrderListener(OrderMessages notificationOrderMessages, JsonMapper jsonMapper) {
        return new OrderNotificationsListener(notificationOrderMessages, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    IdentityNotificationsListener notificationIdentityListener(AccountMessages notificationAccountMessages,
            JsonMapper jsonMapper) {
        return new IdentityNotificationsListener(notificationAccountMessages, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask notificationEmailWorker(NotificationService notificationService) {
        return PeriodicTask.start("notification-email", Duration.ofSeconds(15), Duration.ofSeconds(10),
                notificationService::sendDue);
    }
}
