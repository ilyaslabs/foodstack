package io.github.ilyaslabs.foodstack.notification.core.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.github.ilyaslabs.foodstack.identity.api.IdentityApi;
import io.github.ilyaslabs.foodstack.identity.api.UserView;
import io.github.ilyaslabs.foodstack.notification.core.NotificationConfiguration;
import io.github.ilyaslabs.foodstack.notification.core.application.NotificationService.Message;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport.MailFailure;
import io.github.ilyaslabs.foodstack.notification.core.channel.MailTransport;
import io.github.ilyaslabs.foodstack.platform.mongo.FoodstackMongoProperties;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMigrations;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongo;
import io.github.ilyaslabs.foodstack.platform.mongo.ModuleMongoFactory;
import io.github.ilyaslabs.foodstack.platform.security.PiiCipher;
import io.github.ilyaslabs.foodstack.platform.security.ServiceCalls;
import io.github.ilyaslabs.foodstack.platform.test.FoodstackContainers;
import io.github.ilyaslabs.foodstack.settings.api.BrandingView;
import io.github.ilyaslabs.foodstack.settings.api.SettingsApi;
import io.github.ilyaslabs.moduleswitch.ModuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Messages, the email log and preferences against a real two-shard MongoDB (TDD 15.3). */
@SpringBootTest(classes = NotificationStorageTest.App.class, properties = "spring.data.mongodb.repositories.type=none")
class NotificationStorageTest {

    private static final Instant START = Instant.parse("2026-11-01T12:00:00Z");

    @SpringBootConfiguration
    @EnableAutoConfiguration(excludeName = {
            "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
            "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration" })
    static class App {

        @Bean
        ModuleMongo notificationMongo(ModuleMongoFactory factory) {
            return factory.create("notification");
        }

        @Bean
        ModuleMigrations notificationMigrations(ModuleMongo notificationMongo, ModuleMongoFactory factory,
                FoodstackMongoProperties properties) {
            return new ModuleMigrations(notificationMongo, factory.connectionString("notification"),
                    NotificationConfiguration.shardKeys(), properties);
        }
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        FoodstackContainers.registerMongo(registry);
    }

    @Autowired
    ModuleMongo notificationMongo;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    private final MailTransport mail = mock(MailTransport.class);

    private final IdentityApi identity = mock(IdentityApi.class);

    private final SettingsApi settings = mock(SettingsApi.class);

    private final ServiceCalls serviceCalls = mock(ServiceCalls.class);

    private final PiiCipher pii = new PiiCipher(Base64.getEncoder().encodeToString(new byte[32]));

    private NotificationService notifications;

    private InboxService inbox;

    private String user;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return NotificationStorageTest.this.now.get();
            }
        };
        this.user = "u-" + System.nanoTime();
        given(this.serviceCalls.call(any())).willAnswer((invocation) -> ((Supplier<Object>) invocation.getArgument(0)).get());
        given(this.identity.getUser(anyString())).willReturn(new UserView(this.user, "sara@example.com", "Sara", Set.of(), null, 0,
                Set.of(), Set.of()));
        given(this.settings.getBranding()).willReturn(new BrandingView("Lahore Eats", null, "#B3261E", null, null, null, Map.of(), 1,
                null));
        this.notifications = service(this.mail, clock);
        this.inbox = new InboxService(this.notificationMongo.template(), clock);
    }

    private NotificationService service(MailTransport transport, Clock clock) {
        return new NotificationService(this.notificationMongo.template(), new Templates(JsonMapper.builder().build()), this.settings,
                this.identity, this.serviceCalls, this.pii, transport, "https://eats.example/", "https://eats.example/admin", clock);
    }

    @Test
    @DisplayName("an event becomes one message in the centre and one email, however often it arrives")
    void once() {
        Message message = new Message("e1", this.user, null, "order-accepted", "ur",
                Map.of("number", "7F3A-91C2", "store", "Karahi Corner", "minutes", 15), "/orders/o1", false, true, true, false);
        this.notifications.send(message);
        this.notifications.send(message);

        List<InboxService.Item> items = this.inbox.list(this.user, null, 10);
        assertThat(items).singleElement().satisfies((item) -> {
            assertThat(item.title()).isEqualTo("Karahi Corner نے آپ کا آرڈر قبول کر لیا");
            assertThat(item.link()).isEqualTo("/orders/o1");
            assertThat(item.read()).isFalse();
        });
        assertThat(this.inbox.unread(this.user)).isEqualTo(1);
        this.inbox.markAllRead(this.user);
        assertThat(this.inbox.unread(this.user)).isZero();

        verify(this.mail, times(1)).send(eq("sara@example.com"), eq("آرڈر 7F3A-91C2 قبول ہو گیا"),
                contains("dir=\"rtl\""), contains("https://eats.example/orders/o1"));
        assertThat(this.notifications.deliveries(null, null, 10)).filteredOn((d) -> d.id().startsWith("e1:"))
            .singleElement()
            .satisfies((d) -> {
                assertThat(d.status()).isEqualTo("SENT");
                assertThat(d.recipient()).isEqualTo("s***@example.com");
            });
    }

    @Test
    @DisplayName("a failing email is retried later with longer pauses, then marked failed; support can send it again")
    void retries() {
        willThrow(new MailFailure("Connection refused", false, null)).given(this.mail).send(anyString(), anyString(), anyString(),
                anyString());
        this.notifications.send(new Message("e2", this.user, null, "order-ready-pickup", "en",
                Map.of("number", "A-1", "store", "Karahi Corner"), "/orders/o2", false, false, true, false));
        assertThat(delivery("e2").attempts()).isEqualTo(1);
        assertThat(delivery("e2").status()).isEqualTo("QUEUED");

        this.now.set(START.plus(Duration.ofSeconds(59)));
        this.notifications.sendDue();
        assertThat(delivery("e2").attempts()).isEqualTo(1);
        for (Duration wait : List.of(Duration.ofMinutes(1), Duration.ofMinutes(6), Duration.ofMinutes(36), Duration.ofMinutes(156))) {
            this.now.set(START.plus(wait).plusSeconds(1));
            this.notifications.sendDue();
        }
        NotificationService.DeliveryItem failed = delivery("e2");
        assertThat(failed.attempts()).isEqualTo(5);
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.lastError()).isEqualTo("Connection refused");

        willDoNothing().given(this.mail).send(anyString(), anyString(), anyString(), anyString());
        this.notifications.retry(failed.id());
        assertThat(delivery("e2").status()).isEqualTo("SENT");
        assertThatThrownBy(() -> this.notifications.retry(failed.id())).isInstanceOf(ModuleException.Conflict.class);
    }

    @Test
    @DisplayName("an address the server refuses fails at once")
    void permanent() {
        willThrow(new MailFailure("Invalid address", true, null)).given(this.mail).send(anyString(), anyString(), anyString(),
                anyString());
        this.notifications.send(new Message("e3", this.user, null, "order-delivered", "en", Map.of("number", "A-1", "store", "X"),
                null, false, false, true, false));
        assertThat(delivery("e3").status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("order emails follow the user's choice; required messages and the centre do not")
    void preferences() {
        assertThat(this.notifications.preferences(this.user)).isEqualTo(new NotificationService.Preferences(true, false));
        this.notifications.updatePreferences(this.user, false, false);
        this.notifications.send(new Message("e4", this.user, null, "order-delivered", "en", Map.of("number", "A-1", "store", "X"),
                null, false, true, true, false));
        this.notifications.send(new Message("e5", this.user, null, "login-code", "en", Map.of("code", "123456"), null, false,
                false, true, true));
        assertThat(this.inbox.list(this.user, null, 10)).hasSize(1);
        verify(this.mail, never()).send(anyString(), contains("A-1"), anyString(), anyString());
        verify(this.mail).send(eq("sara@example.com"), contains("123456"), anyString(), anyString());
    }

    @Test
    @DisplayName("without SMTP the email is logged as skipped with the reason")
    void noSmtp() {
        NotificationService without = service(null, Clock.fixed(START, ZoneOffset.UTC));
        without.send(new Message("e6", this.user, null, "order-delivered", "en", Map.of("number", "A-1", "store", "X"), null,
                false, false, true, false));
        assertThat(delivery("e6").status()).isEqualTo("SKIPPED");
        assertThat(delivery("e6").lastError()).contains("SMTP_HOST");
    }

    private NotificationService.DeliveryItem delivery(String eventId) {
        return this.notifications.deliveries(null, null, 100).stream()
            .filter((d) -> d.id().startsWith(eventId + ":"))
            .findFirst()
            .orElseThrow();
    }
}
