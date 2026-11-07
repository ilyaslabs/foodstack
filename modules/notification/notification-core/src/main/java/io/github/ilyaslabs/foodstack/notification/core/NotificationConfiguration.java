package io.github.ilyaslabs.foodstack.notification.core;

import java.time.Clock;
import java.time.Duration;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.notification.core.application.AccountMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.CustomerNotificationData;
import io.github.ilyaslabs.foodstack.notification.core.application.DispatchMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.FinanceMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.InboxService;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService;
import io.github.ilyaslabs.foodstack.notification.core.application.OrderMessages;
import io.github.ilyaslabs.foodstack.notification.core.application.PushService;
import io.github.ilyaslabs.foodstack.notification.core.application.TemplateEditor;
import io.github.ilyaslabs.foodstack.notification.core.application.Templates;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport;
import io.github.ilyaslabs.foodstack.notification.core.channel.SmtpMailTransport;
import io.github.ilyaslabs.foodstack.notification.core.events.DispatchNotificationsListener;
import io.github.ilyaslabs.foodstack.notification.core.events.IdentityNotificationsListener;
import io.github.ilyaslabs.foodstack.notification.core.events.LedgerNotificationsListener;
import io.github.ilyaslabs.foodstack.notification.core.events.OrderNotificationsListener;
import io.github.ilyaslabs.foodstack.notification.core.push.FcmPushTransport;
import io.github.ilyaslabs.foodstack.order.api.OrderApi;
import io.github.ilyaslabs.foodstack.platform.core.PeriodicTask;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.InboxGuard;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.mongo.ShardKeys;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wiring of the notification module (TDD 5.2): its database, the message texts, email over SMTP
 * (when {@code SMTP_HOST} is set), push over FCM (when {@code FCM_SERVICE_ACCOUNT} is set), the
 * event listeners and the email retry worker. It publishes no
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
            .hashed("notification_preferences", "_id")
            .hashed("notification_devices", "userId");
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
    Templates notificationTemplates(JsonMapper jsonMapper, ModuleMongo notificationMongo) {
        return new Templates(jsonMapper, notificationMongo.template());
    }

    @Bean
    TemplateEditor notificationTemplateEditor(Templates notificationTemplates, ModuleMongo notificationMongo,
            SettingsApi settingsApi, Clock clock) {
        return new TemplateEditor(notificationTemplates, notificationMongo.template(), settingsApi, clock);
    }

    @Bean
    NotificationService notificationService(ModuleMongo notificationMongo, Templates notificationTemplates, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls, PiiCipher piiCipher, ObjectProvider<JavaMailSender> mailSender,
            @Value("${foodstack.notification.from-address:}") String fromAddress,
            @Value("${foodstack.notification.from-name:FoodStack}") String fromName,
            @Value("${foodstack.order.web-url:${foodstack.security.issuer:http://localhost:8080}}") String webUrl,
            @Value("${foodstack.notification.admin-url:${foodstack.security.issuer:http://localhost:8080}/admin}") String adminUrl,
            PushService notificationPushService, Clock clock) {
        JavaMailSender sender = mailSender.getIfAvailable();
        MailTransport transport = (sender != null && !fromAddress.isBlank()) ? new SmtpMailTransport(sender, fromAddress, fromName)
                : null;
        NotificationService service = new NotificationService(notificationMongo.template(), notificationTemplates, settingsApi,
                identityApi, serviceCalls, piiCipher, transport, webUrl, adminUrl, clock);
        service.usePush(notificationPushService);
        return service;
    }

    @Bean
    PushService notificationPushService(ModuleMongo notificationMongo, PiiCipher piiCipher, JsonMapper jsonMapper,
            @Value("${foodstack.notification.push.fcm-service-account:}") String serviceAccount,
            @Value("${foodstack.notification.push.fcm-url:https://fcm.googleapis.com}") String fcmUrl, Clock clock) {
        FcmPushTransport transport = null;
        if (!serviceAccount.isBlank()) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(Duration.ofSeconds(5));
            requestFactory.setReadTimeout(Duration.ofSeconds(5));
            transport = new FcmPushTransport(RestClient.builder().requestFactory(requestFactory), jsonMapper,
                    serviceAccountJson(serviceAccount), fcmUrl, clock);
        }
        return new PushService(notificationMongo.template(), transport, piiCipher, clock);
    }

    /** The setting holds the key file's JSON itself, or the path of the key file (the usual case on a server). */
    static String serviceAccountJson(String setting) {
        String value = setting.strip();
        if (value.startsWith("{")) {
            return value;
        }
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(value));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read the FCM service account file " + value, e);
        }
    }

    @Bean
    InboxGuard notificationInbox(ModuleMongo notificationMongo, Clock clock) {
        return new InboxGuard(notificationMongo, clock);
    }

    @Bean
    DispatchMessages notificationDispatchMessages(NotificationService notificationService, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls) {
        return new DispatchMessages(notificationService, settingsApi, identityApi, serviceCalls);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    DispatchNotificationsListener notificationDispatchListener(DispatchMessages notificationDispatchMessages,
            InboxGuard notificationInbox, JsonMapper jsonMapper) {
        return new DispatchNotificationsListener(notificationDispatchMessages, notificationInbox, jsonMapper);
    }

    @Bean
    FinanceMessages notificationFinanceMessages(NotificationService notificationService, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls) {
        return new FinanceMessages(notificationService, settingsApi, identityApi, serviceCalls);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    LedgerNotificationsListener notificationLedgerListener(FinanceMessages notificationFinanceMessages, JsonMapper jsonMapper) {
        return new LedgerNotificationsListener(notificationFinanceMessages, jsonMapper);
    }

    @Bean
    CustomerNotificationData notificationCustomerData(ModuleMongo notificationMongo, NotificationService notificationService) {
        return new CustomerNotificationData(notificationMongo.template(), notificationService);
    }

    @Bean
    InboxService notificationInboxService(ModuleMongo notificationMongo, Clock clock) {
        return new InboxService(notificationMongo.template(), clock);
    }

    @Bean
    OrderMessages notificationOrderMessages(NotificationService notificationService, Templates notificationTemplates,
            SettingsApi settingsApi, OrderApi orderApi, ServiceCalls serviceCalls, PiiCipher piiCipher) {
        return new OrderMessages(notificationService, notificationTemplates, settingsApi, orderApi, serviceCalls, piiCipher);
    }

    @Bean
    AccountMessages notificationAccountMessages(NotificationService notificationService, SettingsApi settingsApi,
            IdentityApi identityApi, ServiceCalls serviceCalls, PiiCipher piiCipher, Clock clock) {
        return new AccountMessages(notificationService, settingsApi, identityApi, serviceCalls, piiCipher, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    OrderNotificationsListener notificationOrderListener(OrderMessages notificationOrderMessages, JsonMapper jsonMapper) {
        return new OrderNotificationsListener(notificationOrderMessages, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    IdentityNotificationsListener notificationIdentityListener(AccountMessages notificationAccountMessages,
            CustomerNotificationData notificationCustomerData, JsonMapper jsonMapper) {
        return new IdentityNotificationsListener(notificationAccountMessages, notificationCustomerData, jsonMapper);
    }

    @Bean
    @ConditionalOnProperty(name = "foodstack.migrate-only", havingValue = "false", matchIfMissing = true)
    PeriodicTask notificationEmailWorker(NotificationService notificationService) {
        return PeriodicTask.start("notification-senders", Duration.ofSeconds(15), Duration.ofSeconds(5),
                notificationService::sendDue);
    }
}
